package com.kauth.infrastructure

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeTenantRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class PortalClientProvisioningTest {
    private val tenants = FakeTenantRepository()
    private val apps = FakeApplicationRepository()
    private val provisioning = PortalClientProvisioning(tenants, apps, baseUrl = "https://sso.example.com")

    private fun portalRedirects(tenant: Tenant) =
        apps.findByClientId(tenant.id, PortalClientProvisioning.PORTAL_CLIENT_ID)!!.redirectUris

    @Test
    fun `the portal client's redirect URI follows the workspace's public domain`() {
        val acme = tenants.add(Tenant(id = TenantId(0), slug = "acme", displayName = "Acme", issuerUrl = null))

        provisioning.provisionForTenant(acme)
        val onInstance = portalRedirects(acme)
        provisioning.provisionForTenant(acme.copy(publicBaseUrl = "https://auth.acme.test"))

        assertEquals(listOf("https://sso.example.com/t/acme/account/callback"), onInstance)
        assertEquals(listOf("https://auth.acme.test/t/acme/account/callback"), portalRedirects(acme))
    }
}
