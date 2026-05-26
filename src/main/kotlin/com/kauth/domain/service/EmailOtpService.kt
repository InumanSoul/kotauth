package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.EmailOtpChallenge
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.AuthorizationCodeRepository
import com.kauth.domain.port.EmailOtpChallengeRepository
import com.kauth.domain.port.EmailPort
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.util.sha256Hex
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Email-OTP passwordless primitive. Owned by Phase 2 of v1.12.0.
 *
 * `sendOtp` is find-or-create-and-send (the creation half is gated by
 * [com.kauth.domain.model.SecurityConfig.emailOtpSignupEnabled]; the response
 * shape is uniform regardless). `verifyOtp` mints a single-use OAuth
 * authorization code that the caller exchanges at the standard `/token`
 * endpoint — the BFF never sees the user id, only the opaque challenge handle
 * and the code.
 *
 * Constant-time padding is intentionally a route-layer concern (the route
 * wraps the call with `delay()`); this service stays sync and framework-free.
 */
class EmailOtpService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val challengeRepository: EmailOtpChallengeRepository,
    private val applicationRepository: ApplicationRepository,
    private val authorizationCodeRepository: AuthorizationCodeRepository,
    private val emailPort: EmailPort,
    private val auditLog: AuditLogPort,
    private val roleRepository: RoleRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(EmailOtpService::class.java)

    companion object {
        const val OTP_TTL_MINUTES: Long = 10
        const val MAX_ATTEMPTS_PER_CHALLENGE: Int = 5
        const val AUTH_CODE_TTL_SECONDS: Long = 60
        private val SECURE_RANDOM = SecureRandom()
    }

    fun sendOtp(
        tenantSlug: String,
        email: String,
        originatingClientId: String? = null,
        ipAddress: String? = null,
    ): OtpSendResult {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return OtpSendResult.Failure(OtpError.TenantNotFound)

        val normalizedEmail = email.trim().lowercase()
        val existing = userRepository.findByEmail(tenant.id, normalizedEmail)
        val now = clock.instant()
        val expiresAt = now.plusSeconds(OTP_TTL_MINUTES * 60)

        val user =
            existing ?: run {
                if (!tenant.securityConfig.emailOtpSignupEnabled) {
                    // Signup disabled — return a uniform success with a handle that never resolves.
                    return OtpSendResult.Success(
                        challengeId = generateChallengeHandle(),
                        expiresAt = expiresAt,
                    )
                }
                userRepository.save(
                    User(
                        tenantId = tenant.id,
                        username = normalizedEmail,
                        email = normalizedEmail,
                        fullName = normalizedEmail.substringBefore("@"),
                        passwordHash = User.SENTINEL_PASSWORD_HASH,
                        emailVerified = false,
                        requiredActions = emptySet<RequiredAction>(),
                    ),
                )
            }

        val invalidated = challengeRepository.deleteActiveByUser(user.id!!)
        val rawCode = generateSixDigitCode()
        val challenge =
            challengeRepository.create(
                EmailOtpChallenge(
                    userId = user.id,
                    tenantId = tenant.id,
                    challengeId = generateChallengeHandle(),
                    codeHash = sha256Hex(rawCode),
                    originatingClientId = originatingClientId,
                    resendCount = if (invalidated > 0) 1 else 0,
                    expiresAt = expiresAt,
                    createdAt = now,
                ),
            )

        val deliveryFailure =
            runCatching {
                emailPort.sendEmailOtpEmail(
                    to = user.email,
                    toName = user.fullName,
                    code = rawCode,
                    expiresInMinutes = OTP_TTL_MINUTES,
                    workspaceName = tenant.emailBranding?.brandName ?: tenant.displayName,
                    tenant = tenant,
                )
            }.exceptionOrNull()
        if (deliveryFailure != null) {
            log.warn(
                "OTP email delivery failed: tenant={} email={} reason={}",
                tenant.slug,
                user.email,
                deliveryFailure.javaClass.simpleName,
            )
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = null,
                eventType = AuditEventType.EMAIL_OTP_SENT,
                ipAddress = ipAddress,
                userAgent = null,
                details =
                    mapOf(
                        "source" to "admin_api",
                        "resend_count" to challenge.resendCount.toString(),
                        "originating_client_id" to (originatingClientId ?: ""),
                    ),
            ),
        )

        return OtpSendResult.Success(challenge.challengeId, challenge.expiresAt)
    }

    fun verifyOtp(
        tenantSlug: String,
        challengeId: String,
        code: String,
        ipAddress: String? = null,
    ): OtpVerifyResult {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return OtpVerifyResult.Failure(OtpError.TenantNotFound)

        val challenge = challengeRepository.findByChallengeId(challengeId)
        if (challenge == null || challenge.tenantId != tenant.id) {
            return reject(tenant.id, null, null, OtpError.InvalidOtp, ipAddress)
        }

        val user = userRepository.findById(challenge.userId, tenant.id)
        if (user == null) {
            return reject(tenant.id, challenge.userId, null, OtpError.InvalidOtp, ipAddress)
        }

        if (isLocked(user)) {
            return reject(tenant.id, user.id, null, OtpError.TooManyAttempts, ipAddress)
        }

        if (challenge.isConsumed || challenge.attemptCount >= MAX_ATTEMPTS_PER_CHALLENGE) {
            return reject(tenant.id, user.id, null, OtpError.TooManyAttempts, ipAddress)
        }

        if (clock.instant().isAfter(challenge.expiresAt)) {
            return reject(tenant.id, user.id, null, OtpError.ChallengeExpired, ipAddress)
        }

        val attempts = challengeRepository.incrementAttempts(challenge.id!!)
        val expectedDigest = challenge.codeHash.toByteArray(Charsets.UTF_8)
        val providedDigest = sha256Hex(code.trim()).toByteArray(Charsets.UTF_8)
        val matches = MessageDigest.isEqual(expectedDigest, providedDigest)

        if (!matches) {
            return if (attempts >= MAX_ATTEMPTS_PER_CHALLENGE) {
                trackCrossChallengeFailure(tenant, user, ipAddress)
                reject(tenant.id, user.id, null, OtpError.TooManyAttempts, ipAddress)
            } else {
                reject(tenant.id, user.id, null, OtpError.InvalidOtp, ipAddress)
            }
        }

        challengeRepository.markConsumed(challenge.id, clock.instant())
        if (!user.emailVerified) {
            userRepository.update(user.copy(emailVerified = true))
        }
        userRepository.resetFailedOtpChallenges(user.id!!)

        applyClientDefaultRolesGrant(
            tenant.id,
            user.id,
            challenge.originatingClientId,
            applicationRepository,
            roleRepository,
        )

        val authCode =
            issueAuthorizationCodeFor(tenant, user.id, challenge.originatingClientId)
                ?: return reject(tenant.id, user.id, null, OtpError.InvalidClient, ipAddress)

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = authCode.clientId,
                eventType = AuditEventType.EMAIL_OTP_VERIFIED,
                ipAddress = ipAddress,
                userAgent = null,
                details = mapOf("challenge_id" to challenge.challengeId),
            ),
        )

        return OtpVerifyResult.Success(authCode.code, authCode.expiresAt, authCode.redirectUri)
    }

    private fun trackCrossChallengeFailure(
        tenant: Tenant,
        user: User,
        ipAddress: String?,
    ) {
        val threshold = tenant.securityConfig.emailOtpLockoutThreshold
        if (threshold <= 0) return

        val newCount = user.failedOtpChallenges + 1
        if (newCount < threshold) {
            userRepository.recordFailedOtpChallenge(user.id!!, newCount, null)
            return
        }

        val lockMinutes = tenant.securityConfig.lockoutDurationMinutes.coerceAtLeast(1)
        val lockUntil = clock.instant().plusSeconds(lockMinutes * 60L)
        userRepository.recordFailedOtpChallenge(user.id!!, 0, lockUntil)

        runCatching {
            emailPort.sendAccountLockedEmail(
                to = user.email,
                toName = user.fullName,
                resetUrl = passwordResetUrl(tenant),
                workspaceName = tenant.emailBranding?.brandName ?: tenant.displayName,
                lockoutDuration = "$lockMinutes minutes",
                tenant = tenant,
            )
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = null,
                eventType = AuditEventType.EMAIL_OTP_LOCKOUT,
                ipAddress = ipAddress,
                userAgent = null,
                details =
                    mapOf(
                        "reason" to "verify_otp_abuse",
                        "threshold" to threshold.toString(),
                        "lockout_minutes" to lockMinutes.toString(),
                    ),
            ),
        )
    }

    private fun issueAuthorizationCodeFor(
        tenant: Tenant,
        userId: UserId,
        clientIdString: String?,
    ): AuthorizationCode? {
        if (clientIdString.isNullOrBlank()) return null
        val client = applicationRepository.findByClientId(tenant.id, clientIdString) ?: return null
        if (!client.enabled) return null
        val redirectUri = client.redirectUris.firstOrNull() ?: return null

        val now = clock.instant()
        return authorizationCodeRepository.save(
            AuthorizationCode(
                code = generateAuthCode(),
                tenantId = tenant.id,
                clientId = client.id,
                userId = userId,
                redirectUri = redirectUri,
                scopes = "openid email profile",
                expiresAt = now.plusSeconds(AUTH_CODE_TTL_SECONDS),
                createdAt = now,
                authTime = now,
            ),
        )
    }

    private fun reject(
        tenantId: TenantId,
        userId: UserId?,
        clientId: com.kauth.domain.model.ApplicationId?,
        error: OtpError,
        ipAddress: String?,
    ): OtpVerifyResult.Failure {
        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = clientId,
                eventType = AuditEventType.EMAIL_OTP_REJECTED,
                ipAddress = ipAddress,
                userAgent = null,
                details = mapOf("reason" to error.code),
            ),
        )
        return OtpVerifyResult.Failure(error)
    }

    private fun isLocked(user: User): Boolean = user.lockedUntil?.isAfter(clock.instant()) == true

    private fun generateSixDigitCode(): String = "%06d".format(SECURE_RANDOM.nextInt(1_000_000))

    private fun generateChallengeHandle(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateAuthCode(): String = "${UUID.randomUUID()}${UUID.randomUUID()}".replace("-", "")

    private fun passwordResetUrl(tenant: Tenant): String =
        tenant.issuerUrl
            ?.replace("/t/${tenant.slug}", "/t/${tenant.slug}/account/forgot-password")
            ?: ""
}

sealed class OtpSendResult {
    data class Success(
        val challengeId: String,
        val expiresAt: Instant,
    ) : OtpSendResult()

    data class Failure(
        val error: OtpError,
    ) : OtpSendResult()
}

sealed class OtpVerifyResult {
    data class Success(
        val authorizationCode: String,
        val codeExpiresAt: Instant,
        val redirectUri: String,
    ) : OtpVerifyResult()

    data class Failure(
        val error: OtpError,
    ) : OtpVerifyResult()
}

enum class OtpError(
    val code: String,
) {
    TenantNotFound("tenant_not_found"),
    InvalidOtp("invalid_otp"),
    ChallengeExpired("challenge_expired"),
    TooManyAttempts("too_many_attempts"),
    InvalidClient("invalid_client"),
}
