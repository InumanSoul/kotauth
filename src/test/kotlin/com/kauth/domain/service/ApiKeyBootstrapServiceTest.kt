package com.kauth.domain.service

import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeTenantRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiKeyBootstrapServiceTest {
    private val apiKeys = FakeApiKeyRepository()
    private val tenants = FakeTenantRepository()
    private val service = ApiKeyBootstrapService(apiKeys, tenants)

    // Named from the registry rather than spelled out: the vendor ids live in one package.
    private val nonDefaultDialect =
        com.kauth.adapter.web.scim.scimDialects
            .last()
            .id

    private lateinit var tenant: Tenant

    @BeforeTest
    fun setUp() {
        apiKeys.clear()
        tenants.clear()
        tenant = tenants.add(Tenant(id = TenantId(0), slug = "zion", displayName = "Zion", issuerUrl = null))
    }

    @Test
    fun `creates a new key when none exists for the (tenant, name) pair`() {
        val result =
            service.ensureBootstrapped(
                listOf(
                    ApiKeyBootstrapService.Entry(
                        tenantSlug = "zion",
                        name = "zion-public-bff",
                        scopes = listOf(ApiScope.AUTH_SEND_OTP, ApiScope.AUTH_VERIFY_OTP),
                        keyHash = "hash-v1",
                    ),
                ),
            )

        assertTrue(result is ApiKeyBootstrapService.Result.Provisioned)
        assertEquals(ApiKeyBootstrapService.Action.CREATED, result.applied.single().action)
        val saved = apiKeys.findByTenantAndName(tenant.id, "zion-public-bff")!!
        assertEquals("hash-v1", saved.keyHash)
        assertEquals("zion-public-bff", saved.bootstrapName)
        assertTrue(saved.enabled)
    }

    @Test
    fun `re-running with the same hash is a no-op`() {
        val entry =
            ApiKeyBootstrapService.Entry(
                tenantSlug = "zion",
                name = "zion-public-bff",
                scopes = listOf(ApiScope.AUTH_SEND_OTP),
                keyHash = "hash-v1",
            )
        service.ensureBootstrapped(listOf(entry))
        val again = service.ensureBootstrapped(listOf(entry))

        assertTrue(again is ApiKeyBootstrapService.Result.Provisioned)
        assertEquals(ApiKeyBootstrapService.Action.UNCHANGED, again.applied.single().action)
    }

    @Test
    fun `rotates the hash in place when value changes`() {
        service.ensureBootstrapped(
            listOf(
                ApiKeyBootstrapService.Entry(
                    tenantSlug = "zion",
                    name = "zion-public-bff",
                    scopes = listOf(ApiScope.AUTH_SEND_OTP),
                    keyHash = "hash-v1",
                ),
            ),
        )
        val rotated =
            service.ensureBootstrapped(
                listOf(
                    ApiKeyBootstrapService.Entry(
                        tenantSlug = "zion",
                        name = "zion-public-bff",
                        scopes = listOf(ApiScope.AUTH_SEND_OTP),
                        keyHash = "hash-v2",
                    ),
                ),
            )

        assertTrue(rotated is ApiKeyBootstrapService.Result.Provisioned)
        assertEquals(ApiKeyBootstrapService.Action.UPDATED, rotated.applied.single().action)
        assertEquals("hash-v2", apiKeys.findByTenantAndName(tenant.id, "zion-public-bff")!!.keyHash)
    }

    @Test
    fun `re-enables a previously revoked bootstrap key on rerun`() {
        val saved =
            apiKeys.save(
                ApiKey(
                    tenantId = tenant.id,
                    name = "zion-public-bff",
                    keyPrefix = "kauth_zion",
                    keyHash = "hash-v1",
                    scopes = listOf(ApiScope.AUTH_SEND_OTP),
                    enabled = false,
                    bootstrapName = "zion-public-bff",
                ),
            )
        val result =
            service.ensureBootstrapped(
                listOf(
                    ApiKeyBootstrapService.Entry(
                        tenantSlug = "zion",
                        name = "zion-public-bff",
                        scopes = listOf(ApiScope.AUTH_SEND_OTP),
                        keyHash = "hash-v1",
                    ),
                ),
            )

        assertTrue(result is ApiKeyBootstrapService.Result.Provisioned)
        assertEquals(ApiKeyBootstrapService.Action.UPDATED, result.applied.single().action)
        assertTrue(apiKeys.findById(saved.id!!, tenant.id)!!.enabled)
    }

    @Test
    fun `fails fast on unknown tenant slug`() {
        val result =
            service.ensureBootstrapped(
                listOf(
                    ApiKeyBootstrapService.Entry(
                        tenantSlug = "unknown",
                        name = "x",
                        scopes = listOf(ApiScope.AUTH_SEND_OTP),
                        keyHash = "h",
                    ),
                ),
            )
        assertTrue(result is ApiKeyBootstrapService.Result.Failure)
    }

    @Test
    fun `default keyPrefix fits the 16-char column ceiling for any tenant slug`() {
        val veryLong = "very-long-brokerage-name-12345"
        tenants.add(Tenant(id = TenantId(0), slug = veryLong, displayName = "X", issuerUrl = null))
        val result =
            service.ensureBootstrapped(
                listOf(
                    ApiKeyBootstrapService.Entry(
                        tenantSlug = veryLong,
                        name = "bff",
                        scopes = listOf(ApiScope.AUTH_SEND_OTP),
                        keyHash = "h",
                    ),
                ),
            )
        assertTrue(result is ApiKeyBootstrapService.Result.Provisioned)
        val saved = apiKeys.findByTenantAndName(tenants.findBySlug(veryLong)!!.id, "bff")!!
        assertTrue(saved.keyPrefix.length <= 16, "keyPrefix '${saved.keyPrefix}' must fit varchar(16)")
    }

    @Test
    fun `fails fast on unknown scope`() {
        val result =
            service.ensureBootstrapped(
                listOf(
                    ApiKeyBootstrapService.Entry(
                        tenantSlug = "zion",
                        name = "x",
                        scopes = listOf("does-not-exist"),
                        keyHash = "h",
                    ),
                ),
            )
        assertTrue(result is ApiKeyBootstrapService.Result.Failure)
    }

    @Test
    fun `provisions a key with the SCIM dialect the entry names`() {
        service.ensureBootstrapped(
            listOf(
                ApiKeyBootstrapService.Entry(
                    tenantSlug = "zion",
                    name = "zion-scim",
                    scopes = listOf(ApiScope.SCIM),
                    keyHash = "hash-v1",
                    scimDialect = nonDefaultDialect,
                ),
            ),
        )

        assertEquals(nonDefaultDialect, apiKeys.findByTenantAndName(tenant.id, "zion-scim")!!.scimDialect)
    }

    @Test
    fun `an entry naming no dialect leaves the key on the RFC default`() {
        service.ensureBootstrapped(
            listOf(
                ApiKeyBootstrapService.Entry(
                    tenantSlug = "zion",
                    name = "zion-scim",
                    scopes = listOf(ApiScope.SCIM),
                    keyHash = "hash-v1",
                ),
            ),
        )

        assertEquals(ApiKey.DEFAULT_SCIM_DIALECT, apiKeys.findByTenantAndName(tenant.id, "zion-scim")!!.scimDialect)
    }

    @Test
    fun `a changed dialect is re-applied to an existing bootstrapped key`() {
        val entry =
            ApiKeyBootstrapService.Entry(
                tenantSlug = "zion",
                name = "zion-scim",
                scopes = listOf(ApiScope.SCIM),
                keyHash = "hash-v1",
            )
        service.ensureBootstrapped(listOf(entry))

        val restarted = service.ensureBootstrapped(listOf(entry.copy(scimDialect = nonDefaultDialect)))

        assertTrue(restarted is ApiKeyBootstrapService.Result.Provisioned)
        assertEquals(ApiKeyBootstrapService.Action.UPDATED, restarted.applied.single().action)
        assertEquals(nonDefaultDialect, apiKeys.findByTenantAndName(tenant.id, "zion-scim")!!.scimDialect)
    }

    @Test
    fun `dropping the dialect from the entry restores the default on the next boot`() {
        val entry =
            ApiKeyBootstrapService.Entry(
                tenantSlug = "zion",
                name = "zion-scim",
                scopes = listOf(ApiScope.SCIM),
                keyHash = "hash-v1",
                scimDialect = nonDefaultDialect,
            )
        service.ensureBootstrapped(listOf(entry))

        service.ensureBootstrapped(listOf(entry.copy(scimDialect = null)))

        assertEquals(ApiKey.DEFAULT_SCIM_DIALECT, apiKeys.findByTenantAndName(tenant.id, "zion-scim")!!.scimDialect)
    }
}
