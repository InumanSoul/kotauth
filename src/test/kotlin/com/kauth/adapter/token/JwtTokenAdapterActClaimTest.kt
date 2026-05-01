package com.kauth.adapter.token

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
 * RFC 8693 §4.1 — when impersonation is active, tokens carry a nested `act`
 * claim with `sub` set to the acting party's id. `sub` itself stays on the
 * impersonated user. Verifies issuance + decode round-trips.
 */
class JwtTokenAdapterActClaimTest {
    private val keyRepo = FakeTenantKeyRepository()
    private val adapter = JwtTokenAdapter(baseUrl = "http://localhost:8080", tenantKeyRepository = keyRepo)

    private val keyPair = KeyGenerator.generateRsaKeyPair("act-key")

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
        )

    private val target =
        User(
            id = UserId(99),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@acme.test",
            fullName = "Alice",
            passwordHash = "x",
        )

    private val adminId = UserId(1)

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

    @Test
    fun `actingSubject null leaves act claim absent on access token`() {
        val response = adapter.issueUserTokens(target, tenant, null, listOf("openid"))

        val decoded =
            com.auth0.jwt.JWT
                .decode(response.access_token)
        assertTrue(decoded.getClaim("act").isMissing || decoded.getClaim("act").isNull)
    }

    @Test
    fun `act claim is stamped on access token when actingSubject is provided`() {
        val response =
            adapter.issueUserTokens(
                user = target,
                tenant = tenant,
                client = null,
                scopes = listOf("openid"),
                actingSubject = adminId,
            )

        val decoded =
            com.auth0.jwt.JWT
                .decode(response.access_token)
        val act = decoded.getClaim("act").asMap()
        assertNotNull(act)
        assertEquals(adminId.value.toString(), act["sub"])
        assertEquals(target.id!!.value.toString(), decoded.subject)
    }

    @Test
    fun `act claim is stamped on id_token when actingSubject is provided`() {
        val response =
            adapter.issueUserTokens(
                user = target,
                tenant = tenant,
                client = null,
                scopes = listOf("openid"),
                actingSubject = adminId,
            )

        assertNotNull(response.id_token)
        val decoded =
            com.auth0.jwt.JWT
                .decode(response.id_token)
        val act = decoded.getClaim("act").asMap()
        assertNotNull(act)
        assertEquals(adminId.value.toString(), act["sub"])
    }

    @Test
    fun `decodeAccessToken surfaces actingSubject for impersonation tokens`() {
        val response =
            adapter.issueUserTokens(
                user = target,
                tenant = tenant,
                client = null,
                scopes = listOf("openid"),
                actingSubject = adminId,
            )

        val claims = adapter.decodeAccessToken(response.access_token)
        assertNotNull(claims)
        assertEquals(target.id!!.value.toString(), claims.sub)
        assertEquals(adminId.value.toString(), claims.actingSubject)
    }

    @Test
    fun `decodeAccessToken returns null actingSubject for normal tokens`() {
        val response = adapter.issueUserTokens(target, tenant, null, listOf("openid"))

        val claims = adapter.decodeAccessToken(response.access_token)
        assertNotNull(claims)
        assertNull(claims.actingSubject)
    }
}
