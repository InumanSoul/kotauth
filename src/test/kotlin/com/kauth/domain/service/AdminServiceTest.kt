package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordPolicyPort
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [AdminService].
 *
 * Covers: workspace settings, user CRUD, application management,
 * client secret regeneration, SMTP config, admin-initiated password reset.
 */
class AdminServiceTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val apps = FakeApplicationRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val sessions = FakeSessionRepository()
    private val passwordPolicy = FakePasswordPolicyPort()
    private val evTokenRepo = FakeEmailVerificationTokenRepository()
    private val prTokenRepo = FakePasswordResetTokenRepository()
    private val emailPort = FakeEmailPort()

    private val selfService =
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

    private val svc =
        AdminService(
            tenantRepository = tenants,
            userRepository = users,
            applicationRepository = apps,
            passwordHasher = hasher,
            auditLog = auditLog,
            sessionRepository = sessions,
            selfServiceService = selfService,
            passwordPolicy = passwordPolicy,
        )

    private val tenant =
        Tenant(
            id = 1,
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            passwordPolicyMinLength = 8,
            smtpHost = "smtp.example.com",
            smtpFromAddress = "no-reply@acme.com",
            smtpEnabled = true,
        )

    private val alice
        get() =
            User(
                id = 10,
                tenantId = 1,
                username = "alice",
                email = "alice@example.com",
                fullName = "Alice Test",
                passwordHash = hasher.hash("pass"),
                enabled = true,
            )

    private val testApp =
        Application(
            id = 100,
            tenantId = 1,
            clientId = "my-app",
            name = "My App",
            description = "Test app",
            accessType = AccessType.CONFIDENTIAL,
            enabled = true,
            redirectUris = listOf("http://localhost/callback"),
        )

    private val defaultTheme = TenantTheme.DEFAULT

    @BeforeTest
    fun setup() {
        tenants.clear()
        users.clear()
        apps.clear()
        auditLog.clear()
        sessions.clear()
        passwordPolicy.clear()
        evTokenRepo.clear()
        prTokenRepo.clear()
        emailPort.clear()
        tenants.add(tenant)
        users.add(alice)
        apps.add(testApp, secretHash = hasher.hash("old-secret"))
    }

    // =========================================================================
    // updateWorkspaceSettings
    // =========================================================================

    @Test
    fun `updateWorkspaceSettings - tenant not found`() {
        val result = callUpdateSettings(slug = "unknown")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - blank display name`() {
        val result = callUpdateSettings(displayName = "  ")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - token expiry too low`() {
        val result = callUpdateSettings(tokenExpirySeconds = 30)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - refresh expiry less than access expiry`() {
        val result = callUpdateSettings(tokenExpirySeconds = 3600, refreshTokenExpirySeconds = 1800)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - password minLength out of range`() {
        val result = callUpdateSettings(passwordPolicyMinLength = 3)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - invalid mfa policy`() {
        val result = callUpdateSettings(mfaPolicy = "invalid")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - success updates tenant`() {
        val result = callUpdateSettings(displayName = "New Name", tokenExpirySeconds = 7200)
        assertIs<AdminResult.Success<Tenant>>(result)
        assertEquals("New Name", result.value.displayName)
        assertEquals(7200L, result.value.tokenExpirySeconds)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_TENANT_UPDATED))
    }

    // =========================================================================
    // createUser
    // =========================================================================

    @Test
    fun `createUser - tenant not found`() {
        val result =
            svc.createUser(
                tenantId = 999,
                username = "bob",
                email = "bob@x.com",
                fullName = "Bob",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `createUser - blank username`() {
        val result =
            svc.createUser(
                tenantId = 1,
                username = "  ",
                email = "bob@x.com",
                fullName = "Bob",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser - invalid username characters`() {
        val result =
            svc.createUser(
                tenantId = 1,
                username = "bad user!",
                email = "bob@x.com",
                fullName = "Bob",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser - invalid email`() {
        val result =
            svc.createUser(
                tenantId = 1,
                username = "bob",
                email = "not-email",
                fullName = "Bob",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser - duplicate username`() {
        val result =
            svc.createUser(
                tenantId = 1,
                username = "alice",
                email = "new@x.com",
                fullName = "Alice 2",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Conflict>(result.error)
    }

    @Test
    fun `createUser - duplicate email`() {
        val result =
            svc.createUser(
                tenantId = 1,
                username = "newuser",
                email = "alice@example.com",
                fullName = "New",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Conflict>(result.error)
    }

    @Test
    fun `createUser - password policy violation`() {
        passwordPolicy.validationError = "Too weak"
        val result =
            svc.createUser(
                tenantId = 1,
                username = "bob",
                email = "bob@x.com",
                fullName = "Bob",
                password = "weak",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser - success`() {
        val result =
            svc.createUser(
                tenantId = 1,
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob Test",
                password = "secure-pass-123",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("bob", result.value.username)
        assertEquals(true, result.value.emailVerified, "Admin-created users should be email-verified")
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_CREATED))
    }

    // =========================================================================
    // updateUser
    // =========================================================================

    @Test
    fun `updateUser - user not found`() {
        val result = svc.updateUser(userId = 999, tenantId = 1, email = "x@x.com", fullName = "X")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateUser - tenant mismatch`() {
        val result = svc.updateUser(userId = 10, tenantId = 99, email = "x@x.com", fullName = "X")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateUser - success`() {
        val result =
            svc.updateUser(
                userId = 10,
                tenantId = 1,
                email = "newalice@example.com",
                fullName = "Alice Updated",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("newalice@example.com", result.value.email)
        assertEquals("Alice Updated", result.value.fullName)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_UPDATED))
    }

    // =========================================================================
    // setUserEnabled
    // =========================================================================

    @Test
    fun `setUserEnabled - user not found`() {
        val result = svc.setUserEnabled(userId = 999, tenantId = 1, enabled = false)
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `setUserEnabled - disables user`() {
        val result = svc.setUserEnabled(userId = 10, tenantId = 1, enabled = false)
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(false, users.findById(10)!!.enabled)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_DISABLED))
    }

    // =========================================================================
    // updateApplication
    // =========================================================================

    @Test
    fun `updateApplication - app not found`() {
        val result =
            svc.updateApplication(
                appId = 999,
                tenantId = 1,
                name = "X",
                description = null,
                accessType = "public",
                redirectUris = emptyList(),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateApplication - tenant mismatch`() {
        val result =
            svc.updateApplication(
                appId = 100,
                tenantId = 99,
                name = "X",
                description = null,
                accessType = "public",
                redirectUris = emptyList(),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateApplication - blank name`() {
        val result =
            svc.updateApplication(
                appId = 100,
                tenantId = 1,
                name = "  ",
                description = null,
                accessType = "public",
                redirectUris = emptyList(),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateApplication - success`() {
        val result =
            svc.updateApplication(
                appId = 100,
                tenantId = 1,
                name = "Renamed App",
                description = "Updated",
                accessType = "public",
                redirectUris = listOf("http://new/callback"),
            )
        assertIs<AdminResult.Success<Application>>(result)
        assertEquals("Renamed App", result.value.name)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_CLIENT_UPDATED))
    }

    // =========================================================================
    // setApplicationEnabled
    // =========================================================================

    @Test
    fun `setApplicationEnabled - disables app`() {
        val result = svc.setApplicationEnabled(appId = 100, tenantId = 1, enabled = false)
        assertIs<AdminResult.Success<Unit>>(result)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_CLIENT_DISABLED))
    }

    // =========================================================================
    // regenerateClientSecret
    // =========================================================================

    @Test
    fun `regenerateClientSecret - app not found`() {
        val result = svc.regenerateClientSecret(appId = 999, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `regenerateClientSecret - success returns raw secret`() {
        val result = svc.regenerateClientSecret(appId = 100, tenantId = 1)
        assertIs<AdminResult.Success<String>>(result)
        assertTrue(result.value.isNotBlank())
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_CLIENT_SECRET_REGENERATED))
    }

    // =========================================================================
    // updateSmtpConfig
    // =========================================================================

    @Test
    fun `updateSmtpConfig - tenant not found`() {
        val result = svc.updateSmtpConfig("unknown", "host", 587, null, null, "a@b.com", null, true, true)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateSmtpConfig - enabled but no host`() {
        val result = svc.updateSmtpConfig("acme", "  ", 587, null, null, "a@b.com", null, true, true)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateSmtpConfig - enabled but invalid from address`() {
        val result = svc.updateSmtpConfig("acme", "smtp.host.com", 587, null, null, "bad-email", null, true, true)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateSmtpConfig - enabled but invalid port`() {
        val result = svc.updateSmtpConfig("acme", "smtp.host.com", 0, null, null, "a@b.com", null, true, true)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateSmtpConfig - disabled skips validation`() {
        val result = svc.updateSmtpConfig("acme", null, 587, null, null, null, null, false, false)
        assertIs<AdminResult.Success<Tenant>>(result)
        assertEquals(false, result.value.smtpEnabled)
    }

    @Test
    fun `updateSmtpConfig - success`() {
        val result =
            svc.updateSmtpConfig(
                "acme",
                "smtp.new.com",
                465,
                "user",
                "pass",
                "no-reply@new.com",
                "New Co",
                true,
                true,
            )
        assertIs<AdminResult.Success<Tenant>>(result)
        assertEquals("smtp.new.com", result.value.smtpHost)
        assertEquals(465, result.value.smtpPort)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_SMTP_UPDATED))
    }

    // =========================================================================
    // sendPasswordResetEmail
    // =========================================================================

    @Test
    fun `sendPasswordResetEmail - user not found`() {
        val result = svc.sendPasswordResetEmail(userId = 999, tenantId = 1, baseUrl = "http://localhost")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `sendPasswordResetEmail - success delegates to self-service`() {
        val result = svc.sendPasswordResetEmail(userId = 10, tenantId = 1, baseUrl = "http://localhost")
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(1, emailPort.sent.size)
        assertEquals("password_reset", emailPort.sent[0].type)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_PASSWORD_RESET))
    }

    // =========================================================================
    // resendVerificationEmail
    // =========================================================================

    @Test
    fun `resendVerificationEmail - success`() {
        val result = svc.resendVerificationEmail(userId = 10, tenantId = 1, baseUrl = "http://localhost")
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(1, emailPort.sent.size)
        assertEquals("verification", emailPort.sent[0].type)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun callUpdateSettings(
        slug: String = "acme",
        displayName: String = "Acme Corp",
        issuerUrl: String? = null,
        tokenExpirySeconds: Long = 3600L,
        refreshTokenExpirySeconds: Long = 86400L,
        registrationEnabled: Boolean = true,
        emailVerificationRequired: Boolean = false,
        passwordPolicyMinLength: Int = 8,
        passwordPolicyRequireSpecial: Boolean = false,
        mfaPolicy: String = "optional",
    ) = svc.updateWorkspaceSettings(
        slug = slug,
        displayName = displayName,
        issuerUrl = issuerUrl,
        tokenExpirySeconds = tokenExpirySeconds,
        refreshTokenExpirySeconds = refreshTokenExpirySeconds,
        registrationEnabled = registrationEnabled,
        emailVerificationRequired = emailVerificationRequired,
        passwordPolicyMinLength = passwordPolicyMinLength,
        passwordPolicyRequireSpecial = passwordPolicyRequireSpecial,
        mfaPolicy = mfaPolicy,
        themeAccentColor = defaultTheme.accentColor,
        themeAccentHover = defaultTheme.accentHoverColor,
        themeBgDeep = defaultTheme.bgDeep,
        themeBgCard = defaultTheme.bgCard,
        themeBgInput = defaultTheme.bgInput,
        themeBorderColor = defaultTheme.borderColor,
        themeBorderRadius = defaultTheme.borderRadius,
        themeTextPrimary = defaultTheme.textPrimary,
        themeTextMuted = defaultTheme.textMuted,
        themeLogoUrl = null,
        themeFaviconUrl = null,
    )
}
