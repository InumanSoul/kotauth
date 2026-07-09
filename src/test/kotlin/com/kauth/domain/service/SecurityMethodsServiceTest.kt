package com.kauth.domain.service

import com.kauth.domain.model.MethodKey
import com.kauth.domain.model.Requirement
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakeTenantRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SecurityMethodsServiceTest {
    private lateinit var tenantRepo: FakeTenantRepository
    private lateinit var idpRepo: FakeIdentityProviderRepository
    private lateinit var service: SecurityMethodsService

    private val tenantId = TenantId(1)

    @BeforeTest
    fun setup() {
        tenantRepo = FakeTenantRepository()
        idpRepo = FakeIdentityProviderRepository()
        service = SecurityMethodsService(tenantRepo, idpRepo)
    }

    private fun tenantOf(
        id: TenantId = tenantId,
        smtpEnabled: Boolean = false,
    ) = Tenant(
        id = id,
        slug = "test",
        displayName = "Test",
        issuerUrl = null,
        smtpHost = if (smtpEnabled) "smtp.example.com" else null,
        smtpFromAddress = if (smtpEnabled) "no-reply@example.com" else null,
        smtpEnabled = smtpEnabled,
        securityConfig = SecurityConfig(),
    )

    @Test
    fun `list returns password and passkey rows always with no requirements`() {
        val tenant = tenantOf()
        tenantRepo.save(tenant)

        val rows = service.list(tenant)
        val keys = rows.map { it.key }

        assertContains(keys, MethodKey.PASSWORD)
        assertContains(keys, MethodKey.PASSKEY)

        val passwordRow = rows.first { it.key == MethodKey.PASSWORD }
        assertTrue(passwordRow.requirements.isEmpty())
        assertTrue(passwordRow.toggleable)
    }

    @Test
    fun `list includes magic link and email otp with SmtpRequired when SMTP not ready`() {
        val tenant = tenantOf(smtpEnabled = false)
        tenantRepo.save(tenant)

        val rows = service.list(tenant)
        val magicLink = rows.first { it.key == MethodKey.MAGIC_LINK }

        assertContains(magicLink.requirements, Requirement.SmtpRequired)
        assertEquals(
            !magicLink.enabled,
            magicLink.requirements.any { it.isBlocking(tenant) { false } },
        )
    }

    @Test
    fun `list includes social rows for unconfigured providers with OAuthCredentialsRequired`() {
        val tenant = tenantOf()
        tenantRepo.save(tenant)

        val rows = service.list(tenant)

        assertTrue(rows.any { it.key == MethodKey.SOCIAL_GOOGLE })
        assertTrue(rows.any { it.key == MethodKey.SOCIAL_GITHUB })
        val googleRow = rows.first { it.key == MethodKey.SOCIAL_GOOGLE }
        assertTrue(googleRow.requirements.any { it is Requirement.OAuthCredentialsRequired })
        assertFalse(googleRow.toggleable)
    }

    @Test
    fun `list includes social rows for configured providers`() {
        val tenant = tenantOf()
        tenantRepo.save(tenant)
        idpRepo.seed(tenantId, provider = "google", enabled = true)

        val rows = service.list(tenant)

        val googleRow = rows.first { it.key == MethodKey.SOCIAL_GOOGLE }
        assertTrue(googleRow.enabled)
        assertFalse(googleRow.requirements.any { it is Requirement.OAuthCredentialsRequired })
        assertTrue(googleRow.toggleable)
    }

    @Test
    fun `list makes social row toggleable when credentials present but disabled`() {
        val tenant = tenantOf()
        tenantRepo.save(tenant)
        idpRepo.seed(tenantId, provider = "google", enabled = false)

        val rows = service.list(tenant)

        val googleRow = rows.first { it.key == MethodKey.SOCIAL_GOOGLE }
        assertFalse(googleRow.enabled)
        assertTrue(googleRow.toggleable)
        assertFalse(googleRow.requirements.any { it is Requirement.OAuthCredentialsRequired })
    }

    @Test
    fun `updateSecurityMethods persists all method flags atomically on happy path`() {
        val tenant = tenantOf(smtpEnabled = true)
        tenantRepo.save(tenant)

        val requested =
            mapOf(
                MethodKey.PASSWORD to true,
                MethodKey.PASSKEY to true,
                MethodKey.MAGIC_LINK to true,
                MethodKey.EMAIL_OTP to false,
            )

        val result = service.updateSecurityMethods(tenantId, requested)

        assertIs<AdminResult.Success<Tenant>>(result)
        val updated = tenantRepo.findById(tenantId)!!
        assertTrue(updated.securityConfig.passwordLoginEnabled)
        assertTrue(updated.passkeysEnabled)
        assertTrue(updated.securityConfig.magicLinkEnabled)
        assertFalse(updated.securityConfig.emailOtpLoginEnabled)
    }

    @Test
    fun `updateSecurityMethods rejects when zero methods enabled`() {
        val tenant = tenantOf()
        tenantRepo.save(tenant)

        val requested = MethodKey.entries.associateWith { false }
        val result = service.updateSecurityMethods(tenantId, requested)

        assertIs<AdminResult.Failure>(result)
        assertEquals(AdminError.NoMethodsEnabled, result.error)
    }

    @Test
    fun `updateSecurityMethods rejects when password disabled and SMTP not ready`() {
        val tenant = tenantOf(smtpEnabled = false)
        tenantRepo.save(tenant)

        val requested =
            mapOf(
                MethodKey.PASSWORD to false,
                MethodKey.PASSKEY to true,
            )
        val result = service.updateSecurityMethods(tenantId, requested)

        assertIs<AdminResult.Failure>(result)
        assertEquals(AdminError.SmtpRequired, result.error)
    }
}
