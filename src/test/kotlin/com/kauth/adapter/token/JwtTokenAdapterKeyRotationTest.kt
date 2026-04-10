package com.kauth.adapter.token

import com.kauth.domain.model.Application
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantKey
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeTenantKeyRepository
import com.kauth.infrastructure.KeyGenerator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [JwtTokenAdapter] key rotation scenarios.
 *
 * Uses real RSA key pairs via [KeyGenerator] and [FakeTenantKeyRepository].
 * No database, no HTTP — tests the signing/verification pipeline directly.
 */
class JwtTokenAdapterKeyRotationTest {
    private val keyRepo = FakeTenantKeyRepository()
    private val adapter = JwtTokenAdapter(baseUrl = "http://localhost:8080", tenantKeyRepository = keyRepo)

    private val keyPairA = KeyGenerator.generateRsaKeyPair("key-a")
    private val keyPairB = KeyGenerator.generateRsaKeyPair("key-b")

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
        )

    private val user =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice Test",
            passwordHash = "hashed",
            enabled = true,
        )

    @BeforeTest
    fun setup() {
        keyRepo.clear()
        keyRepo.add(
            TenantKey(
                tenantId = TenantId(1),
                keyId = keyPairA.keyId,
                publicKeyPem = keyPairA.publicKeyPem,
                privateKeyPem = keyPairA.privateKeyPem,
                enabled = true,
                active = true,
            ),
        )
        adapter.invalidateSigningKeyCache(TenantId(1))
    }

    // =========================================================================
    // kid header presence
    // =========================================================================

    @Test
    fun `issued access token contains kid header matching active key`() {
        val response = adapter.issueUserTokens(user, tenant, null, listOf("openid"))
        val decoded =
            com.auth0.jwt.JWT
                .decode(response.access_token)
        assertEquals(keyPairA.keyId, decoded.keyId, "kid header must match the active key")
    }

    @Test
    fun `issued id_token contains kid header`() {
        val response = adapter.issueUserTokens(user, tenant, null, listOf("openid"))
        assertNotNull(response.id_token)
        val decoded =
            com.auth0.jwt.JWT
                .decode(response.id_token)
        assertEquals(keyPairA.keyId, decoded.keyId)
    }

    @Test
    fun `issued client credentials token contains kid header`() {
        val app =
            Application(
                id =
                    com.kauth.domain.model
                        .ApplicationId(1),
                tenantId = TenantId(1),
                clientId = "my-app",
                name = "My App",
                description = "Test",
                accessType = com.kauth.domain.model.AccessType.CONFIDENTIAL,
                redirectUris = listOf("http://localhost/cb"),
                enabled = true,
            )
        val token = adapter.issueClientCredentialsToken(tenant, app, listOf("openid"))
        val decoded =
            com.auth0.jwt.JWT
                .decode(token)
        assertEquals(keyPairA.keyId, decoded.keyId)
    }

    // =========================================================================
    // Token verification after rotation
    // =========================================================================

    @Test
    fun `token signed by old key verifies after rotation when old key is still enabled`() {
        // Issue token with key A
        val response = adapter.issueUserTokens(user, tenant, null, listOf("openid"))
        val tokenSignedByA = response.access_token

        // Rotate: add key B as active, demote key A
        keyRepo.add(
            TenantKey(
                tenantId = TenantId(1),
                keyId = keyPairB.keyId,
                publicKeyPem = keyPairB.publicKeyPem,
                privateKeyPem = keyPairB.privateKeyPem,
                enabled = true,
                active = false,
            ),
        )
        keyRepo.rotate(TenantId(1), keyPairB.keyId, keyPairA.keyId)
        adapter.invalidateSigningKeyCache(TenantId(1))

        // Token signed by old key A should still verify via kid-based lookup
        val claims = adapter.decodeAccessToken(tokenSignedByA)
        assertNotNull(claims, "Token signed by old key should verify when old key is still enabled")
        assertEquals("alice", claims.username)
    }

    @Test
    fun `token signed by retired key is rejected`() {
        // Issue token with key A
        val response = adapter.issueUserTokens(user, tenant, null, listOf("openid"))
        val tokenSignedByA = response.access_token

        // Rotate to key B, then retire key A
        keyRepo.add(
            TenantKey(
                tenantId = TenantId(1),
                keyId = keyPairB.keyId,
                publicKeyPem = keyPairB.publicKeyPem,
                privateKeyPem = keyPairB.privateKeyPem,
                enabled = true,
                active = false,
            ),
        )
        keyRepo.rotate(TenantId(1), keyPairB.keyId, keyPairA.keyId)
        keyRepo.disable(TenantId(1), keyPairA.keyId)
        adapter.invalidateSigningKeyCache(TenantId(1))

        // Token signed by retired key A should be rejected
        val claims = adapter.decodeAccessToken(tokenSignedByA)
        assertNull(claims, "Token signed by retired key should be rejected")
    }

    @Test
    fun `new tokens after rotation are signed with the new key`() {
        // Rotate to key B
        keyRepo.add(
            TenantKey(
                tenantId = TenantId(1),
                keyId = keyPairB.keyId,
                publicKeyPem = keyPairB.publicKeyPem,
                privateKeyPem = keyPairB.privateKeyPem,
                enabled = true,
                active = false,
            ),
        )
        keyRepo.rotate(TenantId(1), keyPairB.keyId, keyPairA.keyId)
        adapter.invalidateSigningKeyCache(TenantId(1))

        // New token should use key B
        val response = adapter.issueUserTokens(user, tenant, null, listOf("openid"))
        val decoded =
            com.auth0.jwt.JWT
                .decode(response.access_token)
        assertEquals(keyPairB.keyId, decoded.keyId, "New tokens should use the new active key")

        // And it should verify
        val claims = adapter.decodeAccessToken(response.access_token)
        assertNotNull(claims)
    }

    // =========================================================================
    // JWKS
    // =========================================================================

    @Test
    fun `getTenantJwks returns both active and non-active enabled keys after rotation`() {
        // Rotate to key B
        keyRepo.add(
            TenantKey(
                tenantId = TenantId(1),
                keyId = keyPairB.keyId,
                publicKeyPem = keyPairB.publicKeyPem,
                privateKeyPem = keyPairB.privateKeyPem,
                enabled = true,
                active = false,
            ),
        )
        keyRepo.rotate(TenantId(1), keyPairB.keyId, keyPairA.keyId)

        val jwks = adapter.getTenantJwks(TenantId(1))
        assertEquals(2, jwks.size, "JWKS should serve both enabled keys")
        val kids = jwks.map { it["kid"] }
        assertTrue(keyPairA.keyId in kids)
        assertTrue(keyPairB.keyId in kids)
    }

    @Test
    fun `getTenantJwks excludes retired keys`() {
        // Rotate then retire key A
        keyRepo.add(
            TenantKey(
                tenantId = TenantId(1),
                keyId = keyPairB.keyId,
                publicKeyPem = keyPairB.publicKeyPem,
                privateKeyPem = keyPairB.privateKeyPem,
                enabled = true,
                active = false,
            ),
        )
        keyRepo.rotate(TenantId(1), keyPairB.keyId, keyPairA.keyId)
        keyRepo.disable(TenantId(1), keyPairA.keyId)

        val jwks = adapter.getTenantJwks(TenantId(1))
        assertEquals(1, jwks.size, "JWKS should only serve enabled keys")
        assertEquals(keyPairB.keyId, jwks[0]["kid"])
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `decodeAccessToken returns null for malformed token`() {
        assertNull(adapter.decodeAccessToken("not-a-jwt"))
    }

    @Test
    fun `invalidateSigningKeyCache for tenant A does not affect tenant B`() {
        // Setup tenant 2
        val keyPairC = KeyGenerator.generateRsaKeyPair("key-c")
        keyRepo.add(
            TenantKey(
                tenantId = TenantId(2),
                keyId = keyPairC.keyId,
                publicKeyPem = keyPairC.publicKeyPem,
                privateKeyPem = keyPairC.privateKeyPem,
                enabled = true,
                active = true,
            ),
        )
        val tenant2 =
            Tenant(id = TenantId(2), slug = "beta", displayName = "Beta", issuerUrl = null)
        val user2 = user.copy(tenantId = TenantId(2))

        // Issue token for tenant 2 (populates cache)
        val t2Token = adapter.issueUserTokens(user2, tenant2, null, listOf("openid"))

        // Invalidate tenant 1 cache
        adapter.invalidateSigningKeyCache(TenantId(1))

        // Tenant 2 token should still verify (cache intact)
        val claims = adapter.decodeAccessToken(t2Token.access_token)
        assertNotNull(claims, "Tenant 2 token should still verify after tenant 1 cache invalidation")
    }
}
