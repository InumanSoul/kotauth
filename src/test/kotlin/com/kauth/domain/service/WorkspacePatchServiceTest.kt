package com.kauth.domain.service

import com.kauth.domain.model.LoginIdentifierMode
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.SmtpPatch
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.WorkspacePatch
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeThemeRepository
import com.kauth.fakes.FakeTransactionRunner
import com.kauth.fakes.FakeUserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspacePatchServiceTest {
    private val tenantRepo = FakeTenantRepository()
    private val themeRepo = FakeThemeRepository()
    private val userRepo = FakeUserRepository()
    private val auditLog = FakeAuditLogPort()

    private val settingsService =
        WorkspaceSettingsService(tenantRepo, auditLog, themeRepository = themeRepo)

    private val accountService =
        AdminAccountService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            auditLog = auditLog,
            credentialFlowService =
                CredentialFlowService(
                    userRepository = userRepo,
                    tenantRepository = tenantRepo,
                    sessionRepository = FakeSessionRepository(),
                    passwordHasher = FakePasswordHasher(),
                    auditLog = auditLog,
                    evTokenRepo = FakeEmailVerificationTokenRepository(),
                    prTokenRepo = FakePasswordResetTokenRepository(),
                    emailPort = FakeEmailPort(),
                    emailScope = CoroutineScope(Dispatchers.Unconfined),
                ),
        )

    private val service =
        WorkspacePatchService(
            tenantRepository = tenantRepo,
            workspaceSettingsService = settingsService,
            accountService = accountService,
            availableLocales = { setOf("en", "es") },
            transactionRunner = FakeTransactionRunner(tenantRepo),
        )

    private val tenantId = TenantId(7)

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        themeRepo.clear()
        auditLog.clear()
        tenantRepo.add(
            Tenant(
                id = tenantId,
                slug = "shop",
                displayName = "Shop",
                issuerUrl = "https://id.shop.example",
                theme = TenantTheme.DEFAULT,
                securityConfig = SecurityConfig(mfaPolicy = "optional"),
            ),
        )
    }

    private fun stored(): Tenant = tenantRepo.findById(tenantId)!!

    private fun success(patch: WorkspacePatch): Tenant {
        val result = service.patch(tenantId, patch)
        assertIs<AdminResult.Success<Tenant>>(result, "expected success, got $result")
        return result.value
    }

    private val readySmtp =
        SmtpPatch(enabled = true, host = "smtp.shop.example", fromAddress = "noreply@shop.example", password = "pw-1")

    @Test
    fun `an empty patch changes nothing`() {
        val before = stored()
        success(WorkspacePatch())
        assertEquals(before, stored())
    }

    @Test
    fun `only the given fields change`() {
        success(
            WorkspacePatch(
                registrationEnabled = false,
                mfaPolicy = "required",
                tokenExpirySeconds = 600,
                passwordMinLength = 14,
                lockoutMaxAttempts = 3,
            ),
        )
        val t = stored()
        assertFalse(t.registrationEnabled)
        assertEquals("required", t.securityConfig.mfaPolicy)
        assertEquals(600, t.tokenExpirySeconds)
        assertEquals(14, t.securityConfig.passwordMinLength)
        assertEquals(3, t.securityConfig.lockoutMaxAttempts)
        assertEquals("Shop", t.displayName)
        assertEquals("https://id.shop.example", t.issuerUrl)
        assertEquals(LoginIdentifierMode.USERNAME, t.securityConfig.loginIdentifierMode)
    }

    @Test
    fun `an empty issuerUrl clears it`() {
        success(WorkspacePatch(issuerUrl = ""))
        assertNull(stored().issuerUrl)
    }

    @Test
    fun `one patch can configure SMTP, turn on email codes and turn off password sign-in`() {
        success(
            WorkspacePatch(
                smtp = readySmtp,
                passwordLoginEnabled = false,
                emailOtpLoginEnabled = true,
            ),
        )
        val t = stored()
        assertTrue(t.isSmtpReady)
        assertFalse(t.securityConfig.passwordLoginEnabled)
        assertTrue(t.securityConfig.emailOtpLoginEnabled)
    }

    @Test
    fun `an email sign-in method cannot be turned on without SMTP`() {
        val result = service.patch(tenantId, WorkspacePatch(emailOtpLoginEnabled = true))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertFalse(stored().securityConfig.emailOtpLoginEnabled)
    }

    @Test
    fun `the SMTP password is write-only and kept when omitted`() {
        success(WorkspacePatch(smtp = readySmtp))
        success(WorkspacePatch(smtp = SmtpPatch(port = 2525)))
        val t = stored()
        assertEquals("pw-1", t.smtpPassword)
        assertEquals(2525, t.smtpPort)
        assertEquals("smtp.shop.example", t.smtpHost)
    }

    @Test
    fun `SMTP can be switched off without resending the other fields`() {
        success(WorkspacePatch(smtp = readySmtp))
        success(WorkspacePatch(smtp = SmtpPatch(enabled = false)))
        val t = stored()
        assertFalse(t.smtpEnabled)
        assertEquals("smtp.shop.example", t.smtpHost)
    }

    @Test
    fun `defaultLocale is set and cleared through the theme`() {
        assertEquals("es", success(WorkspacePatch(defaultLocale = "ES")).theme.defaultLocale)
        assertEquals("es", themeRepo.findByTenantId(tenantId)?.defaultLocale)
        assertNull(success(WorkspacePatch(defaultLocale = "")).theme.defaultLocale)
        assertNull(themeRepo.findByTenantId(tenantId)?.defaultLocale)
    }

    @Test
    fun `an unknown locale is refused before anything is written`() {
        val result = service.patch(tenantId, WorkspacePatch(defaultLocale = "xx", registrationEnabled = false))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertTrue(stored().registrationEnabled)
    }

    @Test
    fun `a failing step rolls back the SMTP change made before it`() {
        val result = service.patch(tenantId, WorkspacePatch(smtp = readySmtp, mfaPolicy = "sometimes"))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertNull(stored().smtpHost)
        assertFalse(stored().smtpEnabled)
    }

    @Test
    fun `an unknown workspace is not found`() {
        val result = service.patch(TenantId(99), WorkspacePatch(registrationEnabled = false))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `settings changes record the same audit events as the console`() {
        success(WorkspacePatch(smtp = readySmtp, registrationEnabled = false))
        val types = auditLog.events.map { it.eventType.name }
        assertTrue("ADMIN_SMTP_UPDATED" in types, "got $types")
        assertTrue("ADMIN_TENANT_UPDATED" in types, "got $types")
    }
}
