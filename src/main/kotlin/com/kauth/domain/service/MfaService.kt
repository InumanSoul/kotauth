package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.MfaEnrollment
import com.kauth.domain.model.MfaMethod
import com.kauth.domain.model.MfaRecoveryCode
import com.kauth.domain.model.Role
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.MfaRepository
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.infrastructure.TotpUtil
import java.security.SecureRandom
import java.time.Instant

/**
 * Domain service for MFA operations — enrollment, verification, recovery.
 *
 * Workflow:
 *   1. [beginEnrollment] → generates TOTP secret + QR URI + recovery codes
 *   2. User scans QR in authenticator app
 *   3. [verifyEnrollment] → user submits code from app to prove they have it
 *   4. On login, [verifyTotp] or [verifyRecoveryCode] challenges the second factor
 *
 * Per-tenant MFA policy:
 *   - "optional"        → users can self-enroll via portal
 *   - "required"        → all users must enroll; login blocked until complete
 *   - "required_admins" → only users with admin roles must enroll (future)
 */
class MfaService(
    private val mfaRepository: MfaRepository,
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort,
) {
    companion object {
        /** Number of one-time recovery codes generated per enrollment. */
        const val RECOVERY_CODE_COUNT = 8

        /** Length of each plaintext recovery code (hex chars). */
        const val RECOVERY_CODE_LENGTH = 8
    }

    // -----------------------------------------------------------------------
    // Enrollment
    // -----------------------------------------------------------------------

    /**
     * Begins TOTP enrollment for a user. Returns the enrollment plus:
     *   - The otpauth:// URI for QR code generation
     *   - Plaintext recovery codes (shown once, then discarded)
     *
     * If the user already has an unverified enrollment, it is replaced.
     * If the user already has a verified enrollment, this fails.
     */
    fun beginEnrollment(
        userId: UserId,
        tenantId: TenantId,
        issuer: String,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): MfaResult<EnrollmentResponse> {
        val user =
            userRepository.findById(userId)
                ?: return MfaResult.Failure(MfaError.UserNotFound)
        val tenant =
            tenantRepository.findById(tenantId)
                ?: return MfaResult.Failure(MfaError.TenantNotFound)

        // Check for existing enrollment
        val existing = mfaRepository.findEnrollmentByUserId(userId)
        if (existing != null && existing.verified) {
            return MfaResult.Failure(MfaError.AlreadyEnrolled)
        }

        // Replace any unverified enrollment
        if (existing != null) {
            mfaRepository.deleteEnrollmentsByUser(userId)
            mfaRepository.deleteRecoveryCodesByUser(userId)
        }

        // Generate TOTP secret
        val secret = TotpUtil.generateSecret()
        val uri =
            TotpUtil.generateUri(
                secret = secret,
                accountName = user.email,
                issuer = issuer.ifBlank { tenant.displayName },
            )

        // Persist enrollment (unverified)
        val enrollment =
            mfaRepository.saveEnrollment(
                MfaEnrollment(
                    userId = userId,
                    tenantId = tenantId,
                    method = MfaMethod.TOTP,
                    secret = secret,
                ),
            )

        // Generate recovery codes
        val plaintextCodes = generateRecoveryCodes()
        val hashedCodes =
            plaintextCodes.map { code ->
                MfaRecoveryCode(
                    userId = userId,
                    tenantId = tenantId,
                    codeHash = passwordHasher.hash(code),
                )
            }
        mfaRepository.saveRecoveryCodes(hashedCodes)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.MFA_ENROLLMENT_STARTED,
                ipAddress = ipAddress,
                userAgent = userAgent,
            ),
        )

        return MfaResult.Success(
            EnrollmentResponse(
                enrollment = enrollment,
                totpUri = uri,
                recoveryCodes = plaintextCodes,
            ),
        )
    }

    /**
     * Completes enrollment by verifying that the user can produce a valid TOTP code.
     * After this, the enrollment is active and MFA is enforced for the user.
     */
    fun verifyEnrollment(
        userId: UserId,
        code: String,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): MfaResult<MfaEnrollment> {
        val enrollment =
            mfaRepository.findEnrollmentByUserId(userId)
                ?: return MfaResult.Failure(MfaError.NotEnrolled)

        if (enrollment.verified) {
            return MfaResult.Failure(MfaError.AlreadyEnrolled)
        }

        if (!TotpUtil.verify(enrollment.secret, code)) {
            return MfaResult.Failure(MfaError.InvalidCode)
        }

        // Mark enrollment as verified
        val verified =
            mfaRepository.updateEnrollment(
                enrollment.copy(
                    verified = true,
                    verifiedAt = Instant.now(),
                ),
            )

        // Update user's mfaEnabled flag
        val user = userRepository.findById(userId)
        if (user != null) {
            userRepository.update(user.copy(mfaEnabled = true))
        }

        auditLog.record(
            AuditEvent(
                tenantId = enrollment.tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.MFA_ENROLLMENT_VERIFIED,
                ipAddress = ipAddress,
                userAgent = userAgent,
            ),
        )

        return MfaResult.Success(verified)
    }

    // -----------------------------------------------------------------------
    // Login verification
    // -----------------------------------------------------------------------

    /**
     * Verifies a TOTP code during login. Returns true if valid.
     */
    fun verifyTotp(
        userId: UserId,
        code: String,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): MfaResult<Boolean> {
        val enrollment =
            mfaRepository.findEnrollmentByUserId(userId)
                ?: return MfaResult.Failure(MfaError.NotEnrolled)

        if (!enrollment.verified || !enrollment.enabled) {
            return MfaResult.Failure(MfaError.NotEnrolled)
        }

        val valid = TotpUtil.verify(enrollment.secret, code)

        auditLog.record(
            AuditEvent(
                tenantId = enrollment.tenantId,
                userId = userId,
                clientId = null,
                eventType =
                    if (valid) {
                        AuditEventType.MFA_CHALLENGE_SUCCESS
                    } else {
                        AuditEventType.MFA_CHALLENGE_FAILED
                    },
                ipAddress = ipAddress,
                userAgent = userAgent,
            ),
        )

        return if (valid) {
            MfaResult.Success(true)
        } else {
            MfaResult.Failure(MfaError.InvalidCode)
        }
    }

    /**
     * Verifies a one-time recovery code during login (when user has lost their
     * authenticator device). The code is consumed and cannot be reused.
     */
    fun verifyRecoveryCode(
        userId: UserId,
        code: String,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): MfaResult<Boolean> {
        val unusedCodes = mfaRepository.findUnusedRecoveryCodes(userId)
        if (unusedCodes.isEmpty()) {
            return MfaResult.Failure(MfaError.NoRecoveryCodesLeft)
        }

        val enrollment = mfaRepository.findEnrollmentByUserId(userId)

        for (stored in unusedCodes) {
            if (passwordHasher.verify(code, stored.codeHash)) {
                mfaRepository.markRecoveryCodeUsed(stored.id!!)

                auditLog.record(
                    AuditEvent(
                        tenantId = stored.tenantId,
                        userId = userId,
                        clientId = null,
                        eventType = AuditEventType.MFA_RECOVERY_CODE_USED,
                        ipAddress = ipAddress,
                        userAgent = userAgent,
                        details = mapOf("remaining" to (unusedCodes.size - 1).toString()),
                    ),
                )

                return MfaResult.Success(true)
            }
        }

        auditLog.record(
            AuditEvent(
                tenantId = enrollment?.tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.MFA_CHALLENGE_FAILED,
                ipAddress = ipAddress,
                userAgent = userAgent,
            ),
        )

        return MfaResult.Failure(MfaError.InvalidCode)
    }

    // -----------------------------------------------------------------------
    // Disablement
    // -----------------------------------------------------------------------

    /**
     * Disables MFA for a user — removes all enrollments and recovery codes.
     * Typically invoked by an admin or during account recovery.
     */
    fun disableMfa(
        userId: UserId,
        tenantId: TenantId,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): MfaResult<Unit> {
        mfaRepository.deleteEnrollmentsByUser(userId)
        mfaRepository.deleteRecoveryCodesByUser(userId)

        val user = userRepository.findById(userId)
        if (user != null) {
            userRepository.update(user.copy(mfaEnabled = false))
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.MFA_DISABLED,
                ipAddress = ipAddress,
                userAgent = userAgent,
            ),
        )

        return MfaResult.Success(Unit)
    }

    // -----------------------------------------------------------------------
    // Policy check
    // -----------------------------------------------------------------------

    /**
     * Checks whether MFA is required for a given user based on the tenant's policy.
     * Returns true if the user must complete MFA enrollment before login can succeed.
     *
     * @param user            The user attempting to log in.
     * @param tenantMfaPolicy The tenant's configured MFA policy ("optional", "required",
     *                        "required_admins").
     * @param userRoles       The user's effective roles (direct + group-inherited). Only
     *                        consulted when policy is "required_admins". Pass an empty list
     *                        if roles are not available — the policy will not block login in
     *                        that case (conservative fail-open for the required_admins check).
     */
    @Suppress("UNUSED_PARAMETER")
    fun isMfaRequired(
        user: User,
        tenantMfaPolicy: String,
        userRoles: List<Role> = emptyList(),
    ): Boolean =
        when (tenantMfaPolicy) {
            "required" -> true
            // Only users holding the built-in "admin" role are required to enroll MFA.
            // Effective roles (direct assignments + group inheritance + composite expansion)
            // must be passed by the caller — MfaService has no repository access.
            "required_admins" -> userRoles.any { it.name == "admin" }
            else -> false // "optional" — users may self-enroll via the portal
        }

    /**
     * Checks if a user needs to be challenged for MFA during login.
     * True if the user has an active, verified TOTP enrollment.
     */
    fun shouldChallengeMfa(userId: UserId): Boolean {
        val enrollment = mfaRepository.findEnrollmentByUserId(userId)
        return enrollment != null && enrollment.verified && enrollment.enabled
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun generateRecoveryCodes(): List<String> {
        val random = SecureRandom()
        return (1..RECOVERY_CODE_COUNT).map {
            val bytes = ByteArray(RECOVERY_CODE_LENGTH / 2)
            random.nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

// ---------------------------------------------------------------------------
// Response DTOs
// ---------------------------------------------------------------------------

/**
 * Returned from [MfaService.beginEnrollment] — contains everything the client
 * needs to show the QR code and recovery codes to the user.
 */
data class EnrollmentResponse(
    val enrollment: MfaEnrollment,
    val totpUri: String,
    val recoveryCodes: List<String>,
)

// ---------------------------------------------------------------------------
// Result types (same pattern as AuthResult / AdminResult)
// ---------------------------------------------------------------------------

sealed class MfaResult<out T> {
    data class Success<T>(
        val value: T,
    ) : MfaResult<T>()

    data class Failure(
        val error: MfaError,
    ) : MfaResult<Nothing>()
}

sealed class MfaError {
    object UserNotFound : MfaError()

    object TenantNotFound : MfaError()

    object AlreadyEnrolled : MfaError()

    object NotEnrolled : MfaError()

    object InvalidCode : MfaError()

    object NoRecoveryCodesLeft : MfaError()
}
