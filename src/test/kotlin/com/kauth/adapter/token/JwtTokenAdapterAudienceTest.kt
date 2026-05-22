package com.kauth.adapter.token

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
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

/**
 * Verifies the access-token `aud` claim resolution:
 * custom client audience → client_id → tenant slug.
 */
class JwtTokenAdapterAudienceTest {
    private val keyRepo = FakeTenantKeyRepository()
    private val adapter = JwtTokenAdapter(baseUrl = "http://localhost:8080", tenantKeyRepository = keyRepo)
    private val keyPair = KeyGenerator.generateRsaKeyPair("aud-key")

    private val tenant =
        Tenant(id = TenantId(1), slug = "acme", displayName = "Acme", issuerUrl = null)

    private val user =
        User(
            id = UserId(7),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@acme.test",
            fullName = "Alice",
            passwordHash = "x",
        )

    private fun app(audience: String?) =
        Application(
            id = ApplicationId(3),
            tenantId = TenantId(1),
            clientId = "acme-spa",
            name = "Acme SPA",
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://acme.test/cb"),
            audience = audience,
        )

    @BeforeTest
    fun setup() {
        keyRepo.clear()
        keyRepo.add(
            TenantKey(
                tenantId = TenantId(1),
                keyId = keyPair.keyId,
                publicKeyPem = keyPair.publicKeyPem,
                privateKeyPem = keyPair.privateKeyPem,
                enabled = true,
                active = true,
            ),
        )
        adapter.invalidateSigningKeyCache(TenantId(1))
    }

    private fun audOf(token: String) =
        com.auth0.jwt.JWT
            .decode(token)
            .audience
            .first()

    @Test
    fun `custom audience is used for the aud claim when set`() {
        val response =
            adapter.issueUserTokens(user, tenant, app(audience = "acme-resource-api"), listOf("openid"))

        assertEquals("acme-resource-api", audOf(response.access_token))
    }

    @Test
    fun `aud falls back to client_id when audience is null`() {
        val response =
            adapter.issueUserTokens(user, tenant, app(audience = null), listOf("openid"))

        assertEquals("acme-spa", audOf(response.access_token))
    }

    @Test
    fun `aud falls back to tenant slug when there is no client`() {
        val response =
            adapter.issueUserTokens(user, tenant, client = null, scopes = listOf("openid"))

        assertEquals("acme", audOf(response.access_token))
    }
}
