package com.kauth.domain.service

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.MfaEnrollment
import com.kauth.domain.model.MfaMethod
import com.kauth.domain.model.MfaRecoveryCode
import com.kauth.domain.model.Role
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeMfaRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.TotpUtil
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [MfaService].
 *
 * Uses [TotpUtil.generateCode] to produce a valid TOTP code at test time —
 * the same 30-second window tolerance that [TotpUtil.verify] applies means
 * these tests are not timing-sensitive under normal conditions.
 */
class MfaServiceTest {
    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    private val mfaRepo = FakeMfaRepository()
    private val users = FakeUserRepository()
    private val tenants = FakeTenantRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()

    private val svc =
        MfaService(
            mfaRepository = mfaRepo,
            userRepository = users,
            tenantRepository = tenants,
            passwordHasher = hasher,
            auditLog = auditLog,
        )

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private val testTenant = Tenant(id = 1, slug = "acme", displayName = "Acme", issuerUrl = null)
    private val testUser =
        User(
            id = 10,
            tenantId = 1,
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice",
            passwordHash = "hashed:pw",
            enabled = true,
        )

    @BeforeTest
    fun setup() {
        mfaRepo.clear()
        users.clear()
        tenants.clear()
        auditLog.clear()
        tenants.add(testTenant)
        users.add(testUser)
    }

    // =========================================================================
    // beginEnrollment
    // =========================================================================

    @Test
    fun `beginEnrollment returns a TOTP URI and 8 recovery codes on success`() {
        val result = svc.beginEnrollment(userId = 10, tenantId = 1, issuer = "Acme")

        assertIs<MfaResult.Success<*>>(result)
        val response = (result as MfaResult.Success<EnrollmentResponse>).value
        assertTrue(response.totpUri.startsWith("otpauth://totp/"), "TOTP URI must use otpauth scheme")
        assertEquals(
            MfaService.RECOVERY_CODE_COUNT,
            response.recoveryCodes.size,
            "Must generate exactly ${MfaService.RECOVERY_CODE_COUNT} recovery codes",
        )
        assertTrue(auditLog.hasEvent(AuditEventType.MFA_ENROLLMENT_STARTED))
    }

    @Test
    fun `beginEnrollment returns AlreadyEnrolled for user with verified enrollment`() {
        // Seed a verified enrollment directly
        mfaRepo.saveEnrollment(
            MfaEnrollment(
                userId = 10,
                tenantId = 1,
                method = MfaMethod.TOTP,
                secret = TotpUtil.generateSecret(),
                verified = true,
                verifiedAt = Instant.now(),
            ),
        )

        val result = svc.beginEnrollment(userId = 10, tenantId = 1, issuer = "Acme")

        assertIs<MfaResult.Failure>(result)
        assertIs<MfaError.AlreadyEnrolled>(result.error)
    }

    @Test
    fun `beginEnrollment replaces unverified enrollment without error`() {
        // First enrollment (unverified)
        svc.beginEnrollment(userId = 10, tenantId = 1, issuer = "Acme")

        // Second enrollment should succeed — replaces the unverified one
        val result = svc.beginEnrollment(userId = 10, tenantId = 1, issuer = "Acme")

        assertIs<MfaResult.Success<*>>(result)
    }

    // =========================================================================
    // verifyEnrollment
    // =========================================================================

    @Test
    fun `verifyEnrollment returns NotEnrolled when no enrollment exists`() {
        val result = svc.verifyEnrollment(userId = 10, code = "123456")
        assertIs<MfaResult.Failure>(result)
        assertIs<MfaError.NotEnrolled>(result.error)
    }

    @Test
    fun `verifyEnrollment returns InvalidCode for wrong TOTP code`() {
        svc.beginEnrollment(userId = 10, tenantId = 1, issuer = "Acme")

        val result = svc.verifyEnrollment(userId = 10, code = "000000")

        assertIs<MfaResult.Failure>(result)
        assertIs<MfaError.InvalidCode>(result.error)
    }

    @Test
    fun `verifyEnrollment marks enrollment as verified on valid code`() {
        val enrollResult = svc.beginEnrollment(userId = 10, tenantId = 1, issuer = "Acme")
        val secret = (enrollResult as MfaResult.Success<EnrollmentResponse>).value.enrollment.secret
        val validCode = TotpUtil.generateCode(secret)

        val result = svc.verifyEnrollment(userId = 10, code = validCode)

        assertIs<MfaResult.Success<*>>(result)
        val enrollment = mfaRepo.findEnrollmentByUserId(10)
        assertNotNull(enrollment)
        assertTrue(enrollment.verified, "Enrollment must be marked verified after successful verification")
        assertTrue(auditLog.hasEvent(AuditEventType.MFA_ENROLLMENT_VERIFIED))
    }

    // =========================================================================
    // verifyTotp
    // =========================================================================

    @Test
    fun `verifyTotp returns NotEnrolled when user has no enrollment`() {
        val result = svc.verifyTotp(userId = 10, code = "123456")
        assertIs<MfaResult.Failure>(result)
        assertIs<MfaError.NotEnrolled>(result.error)
    }

    @Test
    fun `verifyTotp returns NotEnrolled for unverified enrollment`() {
        // Unverified enrollment — user hasn't finished setup
        mfaRepo.saveEnrollment(
            MfaEnrollment(
                userId = 10,
                tenantId = 1,
                method = MfaMethod.TOTP,
                secret = TotpUtil.generateSecret(),
                verified = false,
            ),
        )

        val result = svc.verifyTotp(userId = 10, code = "123456")

        assertIs<MfaResult.Failure>(result)
        assertIs<MfaError.NotEnrolled>(result.error)
    }

    @Test
    fun `verifyTotp returns InvalidCode for wrong TOTP code`() {
        val secret = TotpUtil.generateSecret()
        mfaRepo.saveEnrollment(
            MfaEnrollment(
                userId = 10,
                tenantId = 1,
                method = MfaMethod.TOTP,
                secret = secret,
                verified = true,
                verifiedAt = Instant.now(),
            ),
        )

        val result = svc.verifyTotp(userId = 10, code = "000000")

        assertIs<MfaResult.Failure>(result)
        assertIs<MfaError.InvalidCode>(result.error)
        assertTrue(auditLog.hasEvent(AuditEventType.MFA_CHALLENGE_FAILED))
    }

    @Test
    fun `verifyTotp succeeds with a valid TOTP code`() {
        val secret = TotpUtil.generateSecret()
        mfaRepo.saveEnrollment(
            MfaEnrollment(
                userId = 10,
                tenantId = 1,
                method = MfaMethod.TOTP,
                secret = secret,
                verified = true,
                verifiedAt = Instant.now(),
            ),
        )
        val validCode = TotpUtil.generateCode(secret)

        val result = svc.verifyTotp(userId = 10, code = validCode)

        assertIs<MfaResult.Success<*>>(result)
        assertTrue(auditLog.hasEvent(AuditEventType.MFA_CHALLENGE_SUCCESS))
    }

    // =========================================================================
    // verifyRecoveryCode
    // =========================================================================

    @Test
    fun `verifyRecoveryCode returns NoRecoveryCodesLeft when no unused codes exist`() {
        // No codes seeded
        val result = svc.verifyRecoveryCode(userId = 10, code = "ABCD1234")
        assertIs<MfaResult.Failure>(result)
        assertIs<MfaError.NoRecoveryCodesLeft>(result.error)
    }

    @Test
    fun `verifyRecoveryCode returns InvalidCode for wrong code`() {
        // Seed one recovery code
        mfaRepo.saveRecoveryCodes(
            listOf(
                MfaRecoveryCode(userId = 10, tenantId = 1, codeHash = hasher.hash("CORRECT1")),
            ),
        )

        val result = svc.verifyRecoveryCode(userId = 10, code = "WRONGCOD")

        assertIs<MfaResult.Failure>(result)
        assertIs<MfaError.InvalidCode>(result.error)
    }

    @Test
    fun `verifyRecoveryCode succeeds and consumes the code — second use must fail`() {
        mfaRepo.saveRecoveryCodes(
            listOf(
                MfaRecoveryCode(userId = 10, tenantId = 1, codeHash = hasher.hash("CORRECT1")),
            ),
        )

        val firstResult = svc.verifyRecoveryCode(userId = 10, code = "CORRECT1")
        assertIs<MfaResult.Success<*>>(firstResult)
        assertTrue(auditLog.hasEvent(AuditEventType.MFA_RECOVERY_CODE_USED))

        // Same code must not work a second time — it's been consumed
        val secondResult = svc.verifyRecoveryCode(userId = 10, code = "CORRECT1")
        assertIs<MfaResult.Failure>(secondResult)
        assertIs<MfaError.NoRecoveryCodesLeft>(secondResult.error)
    }

    // =========================================================================
    // shouldChallengeMfa
    // =========================================================================

    @Test
    fun `shouldChallengeMfa returns false when user has no enrollment`() {
        assertFalse(svc.shouldChallengeMfa(userId = 10))
    }

    @Test
    fun `shouldChallengeMfa returns false for unverified enrollment`() {
        mfaRepo.saveEnrollment(
            MfaEnrollment(
                userId = 10,
                tenantId = 1,
                method = MfaMethod.TOTP,
                secret = TotpUtil.generateSecret(),
                verified = false,
            ),
        )
        assertFalse(svc.shouldChallengeMfa(userId = 10))
    }

    @Test
    fun `shouldChallengeMfa returns true for verified, enabled enrollment`() {
        mfaRepo.saveEnrollment(
            MfaEnrollment(
                userId = 10,
                tenantId = 1,
                method = MfaMethod.TOTP,
                secret = TotpUtil.generateSecret(),
                verified = true,
                enabled = true,
                verifiedAt = Instant.now(),
            ),
        )
        assertTrue(svc.shouldChallengeMfa(userId = 10))
    }

    // =========================================================================
    // disableMfa
    // =========================================================================

    @Test
    fun `disableMfa - removes enrollment and recovery codes`() {
        val enrollResult = svc.beginEnrollment(userId = 10, tenantId = 1, issuer = "Acme")
        val secret = (enrollResult as MfaResult.Success<EnrollmentResponse>).value.enrollment.secret
        val validCode = TotpUtil.generateCode(secret)
        svc.verifyEnrollment(userId = 10, code = validCode)

        assertTrue(svc.shouldChallengeMfa(userId = 10), "MFA should be active before disable")

        val result = svc.disableMfa(userId = 10, tenantId = 1)
        assertIs<MfaResult.Success<Unit>>(result)

        assertFalse(svc.shouldChallengeMfa(userId = 10), "MFA should not challenge after disable")
        val updatedUser = users.findById(10)
        assertNotNull(updatedUser)
        assertFalse(updatedUser.mfaEnabled, "User.mfaEnabled should be false after disable")
        assertTrue(auditLog.hasEvent(AuditEventType.MFA_DISABLED))
    }

    @Test
    fun `disableMfa - succeeds even with no existing enrollment`() {
        val result = svc.disableMfa(userId = 10, tenantId = 1)
        assertIs<MfaResult.Success<Unit>>(result)
        assertTrue(auditLog.hasEvent(AuditEventType.MFA_DISABLED))
    }

    @Test
    fun `disableMfa - clears recovery codes so they cannot be used afterward`() {
        svc.beginEnrollment(userId = 10, tenantId = 1, issuer = "Acme")
        // Seed a known recovery code
        mfaRepo.saveRecoveryCodes(
            listOf(MfaRecoveryCode(userId = 10, tenantId = 1, codeHash = hasher.hash("RECOVERY1"))),
        )

        svc.disableMfa(userId = 10, tenantId = 1)

        val recoveryResult = svc.verifyRecoveryCode(userId = 10, code = "RECOVERY1")
        assertIs<MfaResult.Failure>(recoveryResult)
        assertIs<MfaError.NoRecoveryCodesLeft>(recoveryResult.error)
    }

    // =========================================================================
    // isMfaRequired
    // =========================================================================

    @Test
    fun `isMfaRequired - optional policy returns false`() {
        assertFalse(svc.isMfaRequired(testUser, tenantMfaPolicy = "optional"))
    }

    @Test
    fun `isMfaRequired - required policy returns true for any user`() {
        assertTrue(svc.isMfaRequired(testUser, tenantMfaPolicy = "required"))
    }

    @Test
    fun `isMfaRequired - required_admins returns true only for users with admin role`() {
        val adminRole = Role(id = 1, tenantId = 1, name = "admin")
        val viewerRole = Role(id = 2, tenantId = 1, name = "viewer")

        assertTrue(
            svc.isMfaRequired(testUser, tenantMfaPolicy = "required_admins", userRoles = listOf(adminRole)),
        )
        assertFalse(
            svc.isMfaRequired(testUser, tenantMfaPolicy = "required_admins", userRoles = listOf(viewerRole)),
        )
        assertFalse(
            svc.isMfaRequired(testUser, tenantMfaPolicy = "required_admins", userRoles = emptyList()),
        )
    }

    @Test
    fun `isMfaRequired - unknown policy returns false (fail-open)`() {
        assertFalse(svc.isMfaRequired(testUser, tenantMfaPolicy = "unknown_policy"))
    }
}
