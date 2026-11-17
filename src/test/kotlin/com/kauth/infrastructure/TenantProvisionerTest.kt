package com.kauth.infrastructure

import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantKey
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeTenantKeyRepository
import com.kauth.fakes.FakeTenantRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TenantProvisionerTest {
    private lateinit var tenants: FakeTenantRepository
    private lateinit var keys: FakeTenantKeyRepository
    private lateinit var apps: FakeApplicationRepository
    private lateinit var provisioner: TenantProvisioner

    @BeforeTest
    fun setup() {
        tenants = FakeTenantRepository()
        keys = FakeTenantKeyRepository()
        apps = FakeApplicationRepository()
        provisioner =
            TenantProvisioner(
                KeyProvisioningService(tenants, keys),
                PortalClientProvisioning(tenants, apps, baseUrl = "https://auth.example"),
            )
    }

    @Test
    fun `a tenant without keys gets one active signing key and a portal client`() {
        val tenant = tenants.create(slug = "acme", displayName = "Acme", issuerUrl = null)

        provisioner.provision(tenant)

        assertEquals(1, keys.findAllKeys(tenant.id).size)
        assertNotNull(keys.findActiveKey(tenant.id))
        val portal = assertNotNull(apps.findByClientId(tenant.id, PortalClientProvisioning.PORTAL_CLIENT_ID))
        assertEquals(listOf("https://auth.example/t/acme/account/callback"), portal.redirectUris)
    }

    @Test
    fun `a tenant whose imported keys include an active one keeps exactly those keys`() {
        val tenant = tenants.create(slug = "acme", displayName = "Acme", issuerUrl = null)
        keys.save(
            TenantKey(
                tenantId = tenant.id,
                keyId = "imported-kid",
                publicKeyPem = "pub",
                privateKeyPem = "priv",
                enabled = true,
                active = true,
            ),
        )

        provisioner.provision(tenant)

        assertEquals(listOf("imported-kid"), keys.findAllKeys(tenant.id).map { it.keyId })
    }

    @Test
    fun `provisioning twice is a no-op the second time`() {
        val tenant = tenants.create(slug = "acme", displayName = "Acme", issuerUrl = null)

        provisioner.provision(tenant)
        provisioner.provision(tenant)

        assertEquals(1, keys.findAllKeys(tenant.id).size)
        val portalClients =
            apps.findByTenantId(tenant.id).filter { it.clientId == PortalClientProvisioning.PORTAL_CLIENT_ID }
        assertEquals(1, portalClients.size)
    }

    @Test
    fun `repointing an imported portal client keeps its audience and launcher settings`() {
        val tenant = tenants.create(slug = "acme", displayName = "Acme", issuerUrl = null)
        val imported =
            apps.create(
                tenantId = tenant.id,
                clientId = PortalClientProvisioning.PORTAL_CLIENT_ID,
                name = "Portal",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://old-host.example/t/source/account/callback"),
                grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
                clientSecretHash = null,
                audience = "https://api.acme.example",
            )
        apps.update(
            appId = imported.id,
            name = imported.name,
            description = imported.description,
            accessType = imported.accessType.value,
            redirectUris = imported.redirectUris,
            grantTypes = imported.grantTypes,
            launcherUrl = "https://acme.example/account",
            iconUrl = "https://acme.example/icon.png",
            launcherVisible = false,
            launcherDisplayOrder = 7,
            audience = "https://api.acme.example",
            tokenExpiryOverride = 900,
        )

        provisioner.provision(tenant)

        val portal = assertNotNull(apps.findByClientId(tenant.id, PortalClientProvisioning.PORTAL_CLIENT_ID))
        assertEquals(listOf("https://auth.example/t/acme/account/callback"), portal.redirectUris)
        assertEquals("https://api.acme.example", portal.audience)
        assertEquals("https://acme.example/account", portal.launcherUrl)
        assertEquals("https://acme.example/icon.png", portal.iconUrl)
        assertEquals(false, portal.launcherVisible)
        assertEquals(7, portal.launcherDisplayOrder)
        assertEquals(900, portal.tokenExpiryOverride)
        assertEquals(setOf(GrantType.AUTHORIZATION_CODE), portal.grantTypes)
    }

    @Test
    fun `master gets a signing key but no portal client`() {
        val master = tenants.create(slug = Tenant.MASTER_SLUG, displayName = "Master", issuerUrl = null)

        provisioner.provision(master)

        assertNotNull(keys.findActiveKey(master.id))
        assertNull(apps.findByClientId(master.id, PortalClientProvisioning.PORTAL_CLIENT_ID))
    }
}
