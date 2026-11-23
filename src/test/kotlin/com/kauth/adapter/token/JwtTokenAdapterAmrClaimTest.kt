package com.kauth.adapter.token

import com.kauth.domain.model.AuthMethods
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantKey
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeTenantKeyRepository
import com.kauth.infrastructure.KeyGenerator
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC 8176 `amr`, the PAPE `acr` it earns, and RFC 9470 step-up claims on the access token. */
class JwtTokenAdapterAmrClaimTest {
    private val keyRepo = FakeTenantKeyRepository()
    private val adapter = JwtTokenAdapter(baseUrl = "http://localhost:8080", tenantKeyRepository = keyRepo)

    private val keyPair = KeyGenerator.generateRsaKeyPair("amr-key")

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

    private val authTime = Instant.parse("2026-11-24T10:00:00Z")

    private fun issue(amr: List<String>) =
        adapter.issueUserTokens(target, tenant, null, listOf("openid"), authTime = authTime, amr = amr)

    @Test
    fun `a multi-factor sign-in puts amr and acr in the ID token and acr and auth_time in the access token`() {
        val response = issue(listOf("pwd", "otp", "mfa"))

        val id =
            com.auth0.jwt.JWT
                .decode(response.id_token)
        assertEquals(listOf("pwd", "otp", "mfa"), id.getClaim("amr").asList(String::class.java))
        assertEquals(AuthMethods.ACR_MULTI_FACTOR, id.getClaim("acr").asString())
        val access =
            com.auth0.jwt.JWT
                .decode(response.access_token)
        assertEquals(AuthMethods.ACR_MULTI_FACTOR, access.getClaim("acr").asString())
        assertEquals(authTime.epochSecond, access.getClaim("auth_time").asLong())
        assertTrue(access.getClaim("amr").isMissing, "amr is for the client, not the API")
    }

    @Test
    fun `a single-factor sign-in has amr but no acr`() {
        val response = issue(listOf("pwd"))

        val id =
            com.auth0.jwt.JWT
                .decode(response.id_token)
        assertEquals(listOf("pwd"), id.getClaim("amr").asList(String::class.java))
        assertTrue(id.getClaim("acr").isMissing)
        assertTrue(
            com.auth0.jwt.JWT
                .decode(response.access_token)
                .getClaim("acr")
                .isMissing,
        )
    }

    @Test
    fun `a sign-in with no known methods carries neither claim`() {
        val id =
            com.auth0.jwt.JWT
                .decode(issue(emptyList()).id_token)

        assertTrue(id.getClaim("amr").isMissing)
        assertNull(id.getClaim("acr").asString())
    }
}
