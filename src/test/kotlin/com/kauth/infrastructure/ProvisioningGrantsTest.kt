package com.kauth.infrastructure

import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Tenant
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeThemeRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvisioningGrantsTest {
    private lateinit var tenantRepo: FakeTenantRepository
    private lateinit var applicationRepo: FakeApplicationRepository
    private lateinit var themeRepo: FakeThemeRepository

    @BeforeTest
    fun setup() {
        tenantRepo = FakeTenantRepository()
        applicationRepo = FakeApplicationRepository()
        themeRepo = FakeThemeRepository()
        tenantRepo.create(slug = Tenant.MASTER_SLUG, displayName = "Master", issuerUrl = null)
    }

    private fun provisioning(baseUrl: String = "https://sso.example.com") =
        AdminClientProvisioning(tenantRepo, applicationRepo, themeRepo, baseUrl)

    @Test
    fun `a freshly provisioned admin client can complete an authorization code login`() {
        provisioning().provision()

        val master = tenantRepo.findBySlug(Tenant.MASTER_SLUG)!!
        val admin = applicationRepo.findByClientId(master.id, AdminClientProvisioning.ADMIN_CLIENT_ID)!!

        assertTrue(GrantType.AUTHORIZATION_CODE in admin.grantTypes)
        assertTrue(GrantType.REFRESH_TOKEN in admin.grantTypes)
    }

    @Test
    fun `the admin client is never granted client credentials`() {
        provisioning().provision()

        val master = tenantRepo.findBySlug(Tenant.MASTER_SLUG)!!
        val admin = applicationRepo.findByClientId(master.id, AdminClientProvisioning.ADMIN_CLIENT_ID)!!

        assertTrue(GrantType.CLIENT_CREDENTIALS !in admin.grantTypes)
    }

    @Test
    fun `correcting redirect URI drift preserves the existing grants`() {
        provisioning(baseUrl = "https://old.example.com").provision()

        val master = tenantRepo.findBySlug(Tenant.MASTER_SLUG)!!
        val before = applicationRepo.findByClientId(master.id, AdminClientProvisioning.ADMIN_CLIENT_ID)!!
        assertEquals(listOf("https://old.example.com/admin/callback"), before.redirectUris)

        // Second startup with a changed KAUTH_BASE_URL takes the drift-correction branch.
        provisioning(baseUrl = "https://new.example.com").provision()

        val after = applicationRepo.findByClientId(master.id, AdminClientProvisioning.ADMIN_CLIENT_ID)!!
        assertEquals(listOf("https://new.example.com/admin/callback"), after.redirectUris)
        assertEquals(
            setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN),
            after.grantTypes,
        )
    }

    @Test
    fun `drift correction does not widen a deliberately narrowed grant set`() {
        provisioning(baseUrl = "https://old.example.com").provision()

        val master = tenantRepo.findBySlug(Tenant.MASTER_SLUG)!!
        val app = applicationRepo.findByClientId(master.id, AdminClientProvisioning.ADMIN_CLIENT_ID)!!
        applicationRepo.update(
            appId = app.id,
            name = app.name,
            description = app.description,
            accessType = app.accessType.value,
            redirectUris = app.redirectUris,
            grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
            launcherUrl = null,
            iconUrl = null,
            launcherVisible = true,
            launcherDisplayOrder = 0,
            audience = null,
        )

        provisioning(baseUrl = "https://new.example.com").provision()

        val after = applicationRepo.findByClientId(master.id, AdminClientProvisioning.ADMIN_CLIENT_ID)!!
        assertEquals(setOf(GrantType.AUTHORIZATION_CODE), after.grantTypes)
    }
}
