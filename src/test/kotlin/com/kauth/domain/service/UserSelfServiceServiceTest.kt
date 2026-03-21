package com.kauth.domain.service

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.EmailVerificationToken
import com.kauth.domain.model.PasswordResetToken
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordPolicyPort
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [UserSelfServiceService].
 *
 * Covers: email verification, forgot password, profile update, password change, session management.
 * All I/O replaced by in-memory fakes.
 */
class UserSelfServiceServiceTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val sessions = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val evTokenRepo = FakeEmailVerificationTokenRepository()
    private val prTokenRepo = FakePasswordResetTokenRepository()
    private val emailPort = FakeEmailPort()
    private val passwordPolicy = FakePasswordPolicyPort()

    private val svc =
        UserSelfServiceService(
            userRepository = users,
            tenantRepository = tenants,
            sessionRepository = sessions,
            passwordHasher = hasher,
            auditLog = auditLog,
            evTokenRepo = evTokenRepo,
            prTokenRepo = prTokenRepo,
            emailPort = emailPort,
            passwordPolicy = passwordPolicy,
        )

    // Service instance WITHOUT password policy (fallback to minLength check)
    private val svcNoPolicyPort =
        UserSelfServiceService(
            userRepository = users,
            tenantRepository = tenants,
            sessionRepository = sessions,
            passwordHasher = hasher,
            auditLog = auditLog,
            evTokenRepo = evTokenRepo,
            prTokenRepo = prTokenRepo,
            emailPort = emailPort,
            passwordPolicy = null,
        )

    private val smtpTenant =
        Tenant(
            id = 1,
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            smtpHost = "smtp.example.com",
            smtpFromAddress = "no-reply@acme.com",
            smtpEnabled = true,
            passwordPolicyMinLength = 8,
            passwordPolicyHistoryCount = 3,
        )

    private val noSmtpTenant =
        Tenant(
            id = 2,
            slug = "no-smtp",
            displayName = "No SMTP",
            issuerUrl = null,
        )

    private val alice
        get() =
            User(
                id = 10,
                tenantId = 1,
                username = "alice",
                email = "alice@example.com",
                fullName = "Alice Test",
                passwordHash = hasher.hash("current-pass"),
                emailVerified = false,
            )

    private val verifiedAlice
        get() =
            User(
                id = 11,
                tenantId = 1,
                username = "verified-alice",
                email = "verified@example.com",
                fullName = "Verified Alice",
                passwordHash = hasher.hash("current-pass"),
                emailVerified = true,
            )

    private val disabledUser
        get() =
            User(
                id = 12,
                tenantId = 1,
                username = "disabled",
                email = "disabled@example.com",
                fullName = "Disabled User",
                passwordHash = hasher.hash("pass"),
                enabled = false,
            )

    @BeforeTest
    fun setup() {
        tenants.clear()
        users.clear()
        sessions.clear()
        auditLog.clear()
        evTokenRepo.clear()
        prTokenRepo.clear()
        emailPort.clear()
        passwordPolicy.clear()
        tenants.add(smtpTenant)
        tenants.add(noSmtpTenant)
        users.add(alice)
        users.add(verifiedAlice)
        users.add(disabledUser)
    }

    // =========================================================================
    // initiateEmailVerification
    // =========================================================================

    @Test
    fun `initiateEmailVerification - tenant not found`() {
        val result = svc.initiateEmailVerification(userId = 10, tenantId = 999, baseUrl = "http://localhost")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.NotFound>(result.error)
    }

    @Test
    fun `initiateEmailVerification - user not found`() {
        val result = svc.initiateEmailVerification(userId = 999, tenantId = 1, baseUrl = "http://localhost")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.NotFound>(result.error)
    }

    @Test
    fun `initiateEmailVerification - smtp not configured`() {
        val userInNoSmtp =
            users.add(
                User(
                    tenantId = 2,
                    username = "bob",
                    email = "bob@example.com",
                    fullName = "Bob",
                    passwordHash = hasher.hash("pass"),
                ),
            )
        val result =
            svc.initiateEmailVerification(
                userId = userInNoSmtp.id!!,
                tenantId = 2,
                baseUrl = "http://localhost",
            )
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.SmtpNotConfigured>(result.error)
    }

    @Test
    fun `initiateEmailVerification - already verified returns success noop`() {
        val result = svc.initiateEmailVerification(userId = 11, tenantId = 1, baseUrl = "http://localhost")
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(emailPort.sent.isEmpty(), "No email should be sent for already-verified user")
    }

    @Test
    fun `initiateEmailVerification - sends verification email and creates token`() {
        val result = svc.initiateEmailVerification(userId = 10, tenantId = 1, baseUrl = "http://localhost")
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertEquals(1, emailPort.sent.size)
        assertEquals("verification", emailPort.sent[0].type)
        assertEquals("alice@example.com", emailPort.sent[0].to)
        assertTrue(emailPort.sent[0].url.contains("/t/acme/verify-email?token="))
        assertEquals(1, evTokenRepo.all().size)
        assertTrue(auditLog.hasEvent(AuditEventType.EMAIL_VERIFICATION_SENT))
    }

    @Test
    fun `initiateEmailVerification - deletes unused tokens before creating new one`() {
        svc.initiateEmailVerification(userId = 10, tenantId = 1, baseUrl = "http://localhost")
        assertEquals(1, evTokenRepo.all().size)
        svc.initiateEmailVerification(userId = 10, tenantId = 1, baseUrl = "http://localhost")
        // Old unused token should be deleted, new one created
        assertEquals(1, evTokenRepo.all().size)
    }

    @Test
    fun `initiateEmailVerification - smtp failure returns EmailDeliveryFailed`() {
        emailPort.shouldFail = true
        val result = svc.initiateEmailVerification(userId = 10, tenantId = 1, baseUrl = "http://localhost")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.EmailDeliveryFailed>(result.error)
    }

    // =========================================================================
    // confirmEmailVerification
    // =========================================================================

    @Test
    fun `confirmEmailVerification - invalid token hash`() {
        val result = svc.confirmEmailVerification("nonexistent-raw-token")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.TokenInvalid>(result.error)
    }

    @Test
    fun `confirmEmailVerification - expired token`() {
        val hash = sha256("raw-token")
        evTokenRepo.create(
            EmailVerificationToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().minusSeconds(3600),
            ),
        )
        val result = svc.confirmEmailVerification("raw-token")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.TokenExpired>(result.error)
    }

    @Test
    fun `confirmEmailVerification - already used token`() {
        val hash = sha256("raw-token")
        evTokenRepo.create(
            EmailVerificationToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().plusSeconds(86400),
                usedAt = Instant.now().minusSeconds(100),
            ),
        )
        val result = svc.confirmEmailVerification("raw-token")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.TokenExpired>(result.error)
    }

    @Test
    fun `confirmEmailVerification - success marks user verified and token used`() {
        val hash = sha256("raw-token")
        evTokenRepo.create(
            EmailVerificationToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().plusSeconds(86400),
            ),
        )
        val result = svc.confirmEmailVerification("raw-token")
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(users.findById(10)!!.emailVerified, "User should be marked as email-verified")
        assertNotNull(evTokenRepo.all().first().usedAt, "Token should be marked as used")
        assertTrue(auditLog.hasEvent(AuditEventType.EMAIL_VERIFIED))
    }

    // =========================================================================
    // initiateForgotPassword
    // =========================================================================

    @Test
    fun `initiateForgotPassword - unknown tenant returns success silently`() {
        val result = svc.initiateForgotPassword("alice@example.com", "unknown-slug", "http://localhost", null)
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(emailPort.sent.isEmpty(), "No email for unknown tenant")
    }

    @Test
    fun `initiateForgotPassword - smtp not configured returns success silently`() {
        val result = svc.initiateForgotPassword("alice@example.com", "no-smtp", "http://localhost", null)
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(emailPort.sent.isEmpty())
    }

    @Test
    fun `initiateForgotPassword - unknown email returns success to prevent enumeration`() {
        val result = svc.initiateForgotPassword("nobody@example.com", "acme", "http://localhost", null)
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(emailPort.sent.isEmpty(), "No email for unknown address")
    }

    @Test
    fun `initiateForgotPassword - disabled user returns success silently`() {
        val result = svc.initiateForgotPassword("disabled@example.com", "acme", "http://localhost", null)
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(emailPort.sent.isEmpty(), "No email for disabled user")
    }

    @Test
    fun `initiateForgotPassword - success sends reset email`() {
        val result = svc.initiateForgotPassword("alice@example.com", "acme", "http://localhost", "1.2.3.4")
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertEquals(1, emailPort.sent.size)
        assertEquals("password_reset", emailPort.sent[0].type)
        assertTrue(emailPort.sent[0].url.contains("/t/acme/reset-password?token="))
        assertEquals(1, prTokenRepo.all().size)
        assertTrue(auditLog.hasEvent(AuditEventType.PASSWORD_RESET_REQUESTED))
    }

    @Test
    fun `initiateForgotPassword - smtp failure still returns success`() {
        emailPort.shouldFail = true
        val result = svc.initiateForgotPassword("alice@example.com", "acme", "http://localhost", null)
        assertIs<SelfServiceResult.Success<Unit>>(result)
    }

    // =========================================================================
    // confirmPasswordReset
    // =========================================================================

    @Test
    fun `confirmPasswordReset - invalid token`() {
        val result = svc.confirmPasswordReset("bad-token", "newpass123", "newpass123")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.TokenInvalid>(result.error)
    }

    @Test
    fun `confirmPasswordReset - expired token`() {
        val hash = sha256("raw-reset")
        prTokenRepo.create(
            PasswordResetToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().minusSeconds(3600),
            ),
        )
        val result = svc.confirmPasswordReset("raw-reset", "newpass123", "newpass123")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.TokenExpired>(result.error)
    }

    @Test
    fun `confirmPasswordReset - already used token`() {
        val hash = sha256("raw-reset")
        prTokenRepo.create(
            PasswordResetToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().plusSeconds(3600),
                usedAt = Instant.now().minusSeconds(60),
            ),
        )
        val result = svc.confirmPasswordReset("raw-reset", "newpass123", "newpass123")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.TokenExpired>(result.error)
    }

    @Test
    fun `confirmPasswordReset - blank password`() {
        val hash = sha256("raw-reset")
        prTokenRepo.create(
            PasswordResetToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        val result = svc.confirmPasswordReset("raw-reset", "  ", "  ")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
    }

    @Test
    fun `confirmPasswordReset - passwords do not match`() {
        val hash = sha256("raw-reset")
        prTokenRepo.create(
            PasswordResetToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        val result = svc.confirmPasswordReset("raw-reset", "newpass123", "different123")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
        assertTrue(result.error.message.contains("match"))
    }

    @Test
    fun `confirmPasswordReset - password policy violation`() {
        passwordPolicy.validationError = "Too weak"
        val hash = sha256("raw-reset")
        prTokenRepo.create(
            PasswordResetToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        val result = svc.confirmPasswordReset("raw-reset", "weak", "weak")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
        assertEquals("Too weak", result.error.message)
    }

    @Test
    fun `confirmPasswordReset - password in history`() {
        passwordPolicy.recordPasswordHistory(10, 1, hasher.hash("old-pass"))
        val hash = sha256("raw-reset")
        prTokenRepo.create(
            PasswordResetToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        val result = svc.confirmPasswordReset("raw-reset", "old-pass", "old-pass")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
        assertTrue(result.error.message.contains("recently"))
    }

    @Test
    fun `confirmPasswordReset - success updates password and revokes sessions`() {
        sessions.save(
            Session(
                tenantId = 1,
                userId = 10,
                clientId = null,
                accessTokenHash = "hash1",
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        val hash = sha256("raw-reset")
        prTokenRepo.create(
            PasswordResetToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        val result = svc.confirmPasswordReset("raw-reset", "new-secure-password", "new-secure-password")
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertEquals("hashed:new-secure-password", users.findById(10)!!.passwordHash)
        assertTrue(sessions.findActiveByUser(1, 10).isEmpty(), "All sessions should be revoked")
        assertNotNull(prTokenRepo.all().first().usedAt)
        assertTrue(auditLog.hasEvent(AuditEventType.PASSWORD_RESET_COMPLETED))
    }

    @Test
    fun `confirmPasswordReset - no policy port falls back to minLength check`() {
        val hash = sha256("raw-reset")
        prTokenRepo.create(
            PasswordResetToken(
                userId = 10,
                tenantId = 1,
                tokenHash = hash,
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        val result = svcNoPolicyPort.confirmPasswordReset("raw-reset", "short", "short")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
        assertTrue(result.error.message.contains("at least"))
    }

    // =========================================================================
    // updateProfile
    // =========================================================================

    @Test
    fun `updateProfile - user not found`() {
        val result = svc.updateProfile(userId = 999, tenantId = 1, email = "x@x.com", fullName = "X")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.NotFound>(result.error)
    }

    @Test
    fun `updateProfile - tenant mismatch`() {
        val result = svc.updateProfile(userId = 10, tenantId = 99, email = "x@x.com", fullName = "X")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Unauthorized>(result.error)
    }

    @Test
    fun `updateProfile - invalid email`() {
        val result = svc.updateProfile(userId = 10, tenantId = 1, email = "not-an-email", fullName = "Alice")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
    }

    @Test
    fun `updateProfile - blank full name`() {
        val result = svc.updateProfile(userId = 10, tenantId = 1, email = "alice@example.com", fullName = "  ")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
    }

    @Test
    fun `updateProfile - duplicate email`() {
        val result = svc.updateProfile(userId = 10, tenantId = 1, email = "verified@example.com", fullName = "Alice")
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
        assertTrue(result.error.message.contains("already in use"))
    }

    @Test
    fun `updateProfile - email change resets emailVerified`() {
        // Start with verified user
        val result =
            svc.updateProfile(
                userId = 11,
                tenantId = 1,
                email = "newemail@example.com",
                fullName = "Verified Alice",
            )
        assertIs<SelfServiceResult.Success<User>>(result)
        assertEquals(false, result.value.emailVerified, "Email change should reset verification")
        assertEquals("newemail@example.com", result.value.email)
    }

    @Test
    fun `updateProfile - same email does not reset emailVerified`() {
        val result = svc.updateProfile(userId = 11, tenantId = 1, email = "verified@example.com", fullName = "New Name")
        assertIs<SelfServiceResult.Success<User>>(result)
        assertEquals(true, result.value.emailVerified, "Same email should keep verification")
        assertEquals("New Name", result.value.fullName)
    }

    @Test
    fun `updateProfile - success records audit event`() {
        svc.updateProfile(userId = 10, tenantId = 1, email = "alice@example.com", fullName = "Alice Updated")
        assertTrue(auditLog.hasEvent(AuditEventType.USER_PROFILE_UPDATED))
    }

    // =========================================================================
    // changePassword
    // =========================================================================

    @Test
    fun `changePassword - tenant not found`() {
        val result =
            svc.changePassword(
                userId = 10,
                tenantId = 999,
                currentPassword = "current-pass",
                newPassword = "new-pass-123",
                confirmPassword = "new-pass-123",
            )
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.NotFound>(result.error)
    }

    @Test
    fun `changePassword - user not found`() {
        val result =
            svc.changePassword(
                userId = 999,
                tenantId = 1,
                currentPassword = "x",
                newPassword = "y",
                confirmPassword = "y",
            )
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.NotFound>(result.error)
    }

    @Test
    fun `changePassword - tenant mismatch`() {
        val result =
            svc.changePassword(
                userId = 10,
                tenantId = 2,
                currentPassword = "current-pass",
                newPassword = "new-pass-123",
                confirmPassword = "new-pass-123",
            )
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Unauthorized>(result.error)
    }

    @Test
    fun `changePassword - wrong current password`() {
        val result =
            svc.changePassword(
                userId = 10,
                tenantId = 1,
                currentPassword = "wrong",
                newPassword = "new-pass-123",
                confirmPassword = "new-pass-123",
            )
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
        assertTrue(result.error.message.contains("incorrect"))
    }

    @Test
    fun `changePassword - blank new password`() {
        val result =
            svc.changePassword(
                userId = 10,
                tenantId = 1,
                currentPassword = "current-pass",
                newPassword = "  ",
                confirmPassword = "  ",
            )
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
    }

    @Test
    fun `changePassword - passwords do not match`() {
        val result =
            svc.changePassword(
                userId = 10,
                tenantId = 1,
                currentPassword = "current-pass",
                newPassword = "new-pass-123",
                confirmPassword = "different",
            )
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
    }

    @Test
    fun `changePassword - policy violation`() {
        passwordPolicy.validationError = "Must include special char"
        val result =
            svc.changePassword(
                userId = 10,
                tenantId = 1,
                currentPassword = "current-pass",
                newPassword = "newpassword",
                confirmPassword = "newpassword",
            )
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
    }

    @Test
    fun `changePassword - password in history`() {
        passwordPolicy.recordPasswordHistory(10, 1, hasher.hash("reused-pass"))
        val result =
            svc.changePassword(
                userId = 10,
                tenantId = 1,
                currentPassword = "current-pass",
                newPassword = "reused-pass",
                confirmPassword = "reused-pass",
            )
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
        assertTrue(result.error.message.contains("recently"))
    }

    @Test
    fun `changePassword - no policy port falls back to minLength`() {
        val result =
            svcNoPolicyPort.changePassword(
                userId = 10,
                tenantId = 1,
                currentPassword = "current-pass",
                newPassword = "short",
                confirmPassword = "short",
            )
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Validation>(result.error)
        assertTrue(result.error.message.contains("at least"))
    }

    @Test
    fun `changePassword - success updates password and revokes sessions`() {
        sessions.save(
            Session(
                tenantId = 1,
                userId = 10,
                clientId = null,
                accessTokenHash = "hash1",
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        val result =
            svc.changePassword(
                userId = 10,
                tenantId = 1,
                currentPassword = "current-pass",
                newPassword = "brand-new-pass",
                confirmPassword = "brand-new-pass",
            )
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertEquals("hashed:brand-new-pass", users.findById(10)!!.passwordHash)
        assertTrue(sessions.findActiveByUser(1, 10).isEmpty(), "All sessions should be revoked")
        assertTrue(auditLog.hasEvent(AuditEventType.USER_PASSWORD_CHANGED))
    }

    // =========================================================================
    // getActiveSessions
    // =========================================================================

    @Test
    fun `getActiveSessions - returns active sessions for user`() {
        sessions.save(
            Session(
                tenantId = 1,
                userId = 10,
                clientId = null,
                accessTokenHash = "h1",
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        sessions.save(
            Session(
                tenantId = 1,
                userId = 10,
                clientId = null,
                accessTokenHash = "h2",
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        sessions.save(
            Session(
                tenantId = 1,
                userId = 11,
                clientId = null,
                accessTokenHash = "h3",
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        val result = svc.getActiveSessions(userId = 10, tenantId = 1)
        assertEquals(2, result.size)
    }

    // =========================================================================
    // revokeSession
    // =========================================================================

    @Test
    fun `revokeSession - session not found`() {
        val result = svc.revokeSession(userId = 10, tenantId = 1, sessionId = 999)
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.NotFound>(result.error)
    }

    @Test
    fun `revokeSession - cannot revoke another users session`() {
        val session =
            sessions.save(
                Session(
                    tenantId = 1,
                    userId = 11,
                    clientId = null,
                    accessTokenHash = "h1",
                    refreshTokenHash = null,
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )
        val result = svc.revokeSession(userId = 10, tenantId = 1, sessionId = session.id!!)
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Unauthorized>(result.error)
    }

    @Test
    fun `revokeSession - cannot revoke session from different tenant`() {
        val session =
            sessions.save(
                Session(
                    tenantId = 1,
                    userId = 10,
                    clientId = null,
                    accessTokenHash = "h1",
                    refreshTokenHash = null,
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )
        val result = svc.revokeSession(userId = 10, tenantId = 2, sessionId = session.id!!)
        assertIs<SelfServiceResult.Failure>(result)
        assertIs<SelfServiceError.Unauthorized>(result.error)
    }

    @Test
    fun `revokeSession - success revokes and records audit`() {
        val session =
            sessions.save(
                Session(
                    tenantId = 1,
                    userId = 10,
                    clientId = null,
                    accessTokenHash = "h1",
                    refreshTokenHash = null,
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )
        val result = svc.revokeSession(userId = 10, tenantId = 1, sessionId = session.id!!)
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(sessions.findActiveByUser(1, 10).isEmpty())
        assertTrue(auditLog.hasEvent(AuditEventType.USER_SESSION_REVOKED_SELF))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
