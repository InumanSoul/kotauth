package com.kauth.domain.service

import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeTenantRepository
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ApiKeyService].
 *
 * Covers: key creation, validation, listing, revocation, deletion.
 */
class ApiKeyServiceTest {
    private val apiKeys = FakeApiKeyRepository()
    private val tenants = FakeTenantRepository()

    private val svc = ApiKeyService(apiKeyRepository = apiKeys, tenantRepository = tenants)

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
        )

    @BeforeTest
    fun setup() {
        apiKeys.clear()
        tenants.clear()
        tenants.add(tenant)
    }

    // =========================================================================
    // create
    // =========================================================================

    @Test
    fun `create - blank name`() {
        val result =
            svc.create(
                tenantId = TenantId(1),
                name = "  ",
                scopes = listOf(ApiScope.USERS_READ),
            )
        assertIs<ApiKeyResult.Failure>(result)
        assertIs<ApiKeyError.Validation>(result.error)
    }

    @Test
    fun `create - name too long`() {
        val result =
            svc.create(
                tenantId = TenantId(1),
                name = "a".repeat(129),
                scopes = listOf(ApiScope.USERS_READ),
            )
        assertIs<ApiKeyResult.Failure>(result)
        assertIs<ApiKeyError.Validation>(result.error)
    }

    @Test
    fun `create - tenant not found`() {
        val result =
            svc.create(
                tenantId = TenantId(999),
                name = "CI Key",
                scopes = listOf(ApiScope.USERS_READ),
            )
        assertIs<ApiKeyResult.Failure>(result)
        assertIs<ApiKeyError.NotFound>(result.error)
    }

    @Test
    fun `create - no valid scopes`() {
        val result =
            svc.create(
                tenantId = TenantId(1),
                name = "Bad Key",
                scopes = listOf("invalid:scope"),
            )
        assertIs<ApiKeyResult.Failure>(result)
        assertIs<ApiKeyError.Validation>(result.error)
    }

    @Test
    fun `create - filters invalid scopes keeps valid ones`() {
        val result =
            svc.create(
                tenantId = TenantId(1),
                name = "Mixed Key",
                scopes = listOf(ApiScope.USERS_READ, "bogus:scope"),
            )
        assertIs<ApiKeyResult.Success<CreatedApiKey>>(result)
        assertEquals(listOf(ApiScope.USERS_READ), result.value.apiKey.scopes)
    }

    @Test
    fun `create - success returns raw key and persists hash`() {
        val result =
            svc.create(
                tenantId = TenantId(1),
                name = "CI Pipeline",
                scopes = listOf(ApiScope.USERS_READ, ApiScope.USERS_WRITE),
            )
        assertIs<ApiKeyResult.Success<CreatedApiKey>>(result)

        val created = result.value
        assertTrue(created.rawKey.startsWith("kauth_acme_"), "Key should start with kauth_<slug>_")
        assertEquals("CI Pipeline", created.apiKey.name)
        assertEquals(listOf(ApiScope.USERS_READ, ApiScope.USERS_WRITE), created.apiKey.scopes)
        assertTrue(created.apiKey.enabled)
        assertNotNull(created.apiKey.id)

        // Verify hash is stored correctly
        val hash = sha256(created.rawKey)
        assertEquals(hash, created.apiKey.keyHash)
        assertNotNull(apiKeys.findByHash(hash))
    }

    @Test
    fun `create - with expiry`() {
        val expiry = Instant.now().plusSeconds(86400)
        val result =
            svc.create(
                tenantId = TenantId(1),
                name = "Temp Key",
                scopes = listOf(ApiScope.USERS_READ),
                expiresAt = expiry,
            )
        assertIs<ApiKeyResult.Success<CreatedApiKey>>(result)
        assertEquals(expiry, result.value.apiKey.expiresAt)
    }

    // =========================================================================
    // validate
    // =========================================================================

    @Test
    fun `validate - unknown key returns null`() {
        assertNull(svc.validate("kauth_acme_nonexistent", expectedTenantId = TenantId(1)))
    }

    @Test
    fun `validate - valid key returns api key`() {
        val created =
            (svc.create(TenantId(1), "Key", listOf(ApiScope.USERS_READ)) as ApiKeyResult.Success).value
        val result = svc.validate(created.rawKey, expectedTenantId = TenantId(1))
        assertNotNull(result)
        assertEquals(created.apiKey.id, result.id)
    }

    @Test
    fun `validate - wrong tenant returns null`() {
        val created =
            (svc.create(TenantId(1), "Key", listOf(ApiScope.USERS_READ)) as ApiKeyResult.Success).value
        assertNull(svc.validate(created.rawKey, expectedTenantId = TenantId(99)))
    }

    @Test
    fun `validate - disabled key returns null`() {
        val created =
            (svc.create(TenantId(1), "Key", listOf(ApiScope.USERS_READ)) as ApiKeyResult.Success).value
        apiKeys.revoke(created.apiKey.id!!, TenantId(1))
        assertNull(svc.validate(created.rawKey, expectedTenantId = TenantId(1)))
    }

    @Test
    fun `validate - expired key returns null`() {
        val expiry = Instant.now().minusSeconds(3600) // already expired
        val hash = sha256("kauth_acme_expired-key")
        apiKeys.save(
            ApiKey(
                tenantId = TenantId(1),
                name = "Expired",
                keyPrefix = "kauth_acme_exp",
                keyHash = hash,
                scopes = listOf(ApiScope.USERS_READ),
                expiresAt = expiry,
                enabled = true,
            ),
        )
        assertNull(svc.validate("kauth_acme_expired-key", expectedTenantId = TenantId(1)))
    }

    // =========================================================================
    // listForTenant
    // =========================================================================

    @Test
    fun `listForTenant - returns keys for tenant only`() {
        svc.create(TenantId(1), "Key1", listOf(ApiScope.USERS_READ))
        svc.create(TenantId(1), "Key2", listOf(ApiScope.ROLES_READ))
        assertEquals(2, svc.listForTenant(TenantId(1)).size)
        assertEquals(0, svc.listForTenant(TenantId(99)).size)
    }

    // =========================================================================
    // revoke
    // =========================================================================

    @Test
    fun `revoke - not found`() {
        val result = svc.revoke(id = 999, tenantId = TenantId(1))
        assertIs<ApiKeyResult.Failure>(result)
        assertIs<ApiKeyError.NotFound>(result.error)
    }

    @Test
    fun `revoke - success disables key`() {
        val created =
            (svc.create(TenantId(1), "Key", listOf(ApiScope.USERS_READ)) as ApiKeyResult.Success).value
        val result = svc.revoke(created.apiKey.id!!, TenantId(1))
        assertIs<ApiKeyResult.Success<Unit>>(result)
        assertEquals(false, apiKeys.findByHash(created.apiKey.keyHash)!!.enabled)
    }

    // =========================================================================
    // delete
    // =========================================================================

    @Test
    fun `delete - not found`() {
        val result = svc.delete(id = 999, tenantId = TenantId(1))
        assertIs<ApiKeyResult.Failure>(result)
    }

    @Test
    fun `delete - success removes key`() {
        val created =
            (svc.create(TenantId(1), "Key", listOf(ApiScope.USERS_READ)) as ApiKeyResult.Success).value
        val result = svc.delete(created.apiKey.id!!, TenantId(1))
        assertIs<ApiKeyResult.Success<Unit>>(result)
        assertNull(apiKeys.findByHash(created.apiKey.keyHash))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
