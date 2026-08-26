package com.kauth.adapter.social

import com.kauth.domain.port.OidcDiscoveryFailure
import com.kauth.domain.port.OidcEndpointOverrides
import com.kauth.fakes.FakeHttpJsonFetcher
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [HttpOidcDiscoveryAdapter]. The suite has no network, so every fetch
 * goes through [FakeHttpJsonFetcher].
 */
class HttpOidcDiscoveryAdapterTest {
    private val fetcher = FakeHttpJsonFetcher()
    private var now = 0L
    private val ttlMillis = 3_600_000L
    private val adapter = HttpOidcDiscoveryAdapter(fetcher, ttlMillis = ttlMillis, clock = { now })

    private val issuer = "https://issuer.example"
    private val wellKnown = "$issuer/.well-known/openid-configuration"

    private fun document(
        declaredIssuer: String = issuer,
        jwksUri: String? = "$issuer/jwks",
    ): String {
        val jwks = jwksUri?.let { """ "jwks_uri": "$it", """ } ?: ""
        return """
            {
              "issuer": "$declaredIssuer",
              "authorization_endpoint": "$issuer/authorize",
              "token_endpoint": "$issuer/token",
              $jwks
              "end_session_endpoint": "$issuer/logout"
            }
            """.trimIndent()
    }

    @BeforeTest
    fun setUp() {
        fetcher.clear()
        now = 0L
    }

    @Test
    fun `a well-formed document resolves to its endpoints`() {
        fetcher.respondWith(wellKnown, document())

        val discovery = adapter.discover(issuer).getOrThrow()

        assertEquals(issuer, discovery.issuer)
        assertEquals("$issuer/authorize", discovery.authorizationEndpoint)
        assertEquals("$issuer/token", discovery.tokenEndpoint)
        assertEquals("$issuer/jwks", discovery.jwksUri)
        assertEquals("$issuer/logout", discovery.endSessionEndpoint)
        assertEquals(1, fetcher.callCount(wellKnown))
    }

    @Test
    fun `a document declaring a different issuer is rejected`() {
        fetcher.respondWith(wellKnown, document(declaredIssuer = "https://attacker.example"))

        val result = adapter.discover(issuer)

        val failure = assertIs<OidcDiscoveryFailure>(result.exceptionOrNull())
        assertEquals(OidcDiscoveryFailure.Reason.ISSUER_MISMATCH, failure.reason)
        // Nothing from the rejected document may be handed on — the endpoints and the JWKS URI
        // in it would otherwise be the attacker's choice, and every later check would pass.
        assertNull(result.getOrNull())
    }

    @Test
    fun `a mismatched document is not cached and does not poison a later good fetch`() {
        fetcher.respondWith(wellKnown, document(declaredIssuer = "https://attacker.example"))
        assertTrue(adapter.discover(issuer).isFailure)

        fetcher.respondWith(wellKnown, document())
        assertEquals("$issuer/jwks", adapter.discover(issuer).getOrThrow().jwksUri)
    }

    @Test
    fun `a document missing an endpoint is rejected`() {
        fetcher.respondWith(wellKnown, document(jwksUri = null))

        val failure = assertIs<OidcDiscoveryFailure>(adapter.discover(issuer).exceptionOrNull())
        assertEquals(OidcDiscoveryFailure.Reason.MALFORMED, failure.reason)
    }

    @Test
    fun `a cached document is reused within the ttl and refetched after it`() {
        fetcher.respondWith(wellKnown, document())

        adapter.discover(issuer).getOrThrow()
        now += ttlMillis - 1
        adapter.discover(issuer).getOrThrow()
        assertEquals(1, fetcher.callCount(wellKnown))

        now += 1
        adapter.discover(issuer).getOrThrow()
        assertEquals(2, fetcher.callCount(wellKnown))
    }

    @Test
    fun `a complete set of pinned endpoints answers without any fetch`() {
        val overrides =
            OidcEndpointOverrides(
                authorizationEndpoint = "https://pinned.example/authorize",
                tokenEndpoint = "https://pinned.example/token",
                jwksUri = "https://pinned.example/jwks",
            )

        val discovery = adapter.discover(issuer, overrides).getOrThrow()

        assertEquals("https://pinned.example/authorize", discovery.authorizationEndpoint)
        assertEquals("https://pinned.example/token", discovery.tokenEndpoint)
        assertEquals("https://pinned.example/jwks", discovery.jwksUri)
        assertEquals(0, fetcher.requestedUrls.size)
    }

    @Test
    fun `a single pinned endpoint wins over the fetched document`() {
        fetcher.respondWith(wellKnown, document())

        val discovery =
            adapter.discover(issuer, OidcEndpointOverrides(jwksUri = "https://pinned.example/jwks")).getOrThrow()

        assertEquals("https://pinned.example/jwks", discovery.jwksUri)
        assertEquals("$issuer/authorize", discovery.authorizationEndpoint)
        assertEquals(1, fetcher.callCount(wellKnown))
    }

    @Test
    fun `an issuer with a trailing slash is not double-slashed on the well-known path`() {
        val slashed = "$issuer/"
        fetcher.respondWith(wellKnown, document(declaredIssuer = slashed))

        assertEquals(slashed, adapter.discover(slashed).getOrThrow().issuer)
        assertEquals(listOf(wellKnown), fetcher.requestedUrls)
    }

    @Test
    fun `a transport failure returns a failure and no endpoints`() {
        fetcher.shouldFail = true

        val result = adapter.discover(issuer)

        val failure = assertIs<OidcDiscoveryFailure>(result.exceptionOrNull())
        assertEquals(OidcDiscoveryFailure.Reason.FETCH_FAILED, failure.reason)
        assertNull(result.getOrNull())
    }

    @Test
    fun `a non-200 response returns a failure`() {
        fetcher.respondWith(wellKnown, "not found", statusCode = 404)

        val failure = assertIs<OidcDiscoveryFailure>(adapter.discover(issuer).exceptionOrNull())
        assertEquals(OidcDiscoveryFailure.Reason.FETCH_FAILED, failure.reason)
    }

    // -- scheme enforcement -------------------------------------------------

    @Test
    fun `an http issuer is refused before any fetch is made`() {
        val insecure = "http://issuer.example"
        fetcher.respondWith("$insecure/.well-known/openid-configuration", document(declaredIssuer = insecure))

        val result = adapter.discover(insecure)

        val failure = assertIs<OidcDiscoveryFailure>(result.exceptionOrNull())
        assertEquals(OidcDiscoveryFailure.Reason.INSECURE_URL, failure.reason)
        // Over plaintext the issuer check compares the attacker's value with itself, so the
        // document must never be fetched at all.
        assertEquals(0, fetcher.requestedUrls.size)
    }

    @Test
    fun `a document publishing an http endpoint is refused and never cached`() {
        fetcher.respondWith(wellKnown, endpointDocument(jwksUri = "http://issuer.example/jwks"))

        val failure = assertIs<OidcDiscoveryFailure>(adapter.discover(issuer).exceptionOrNull())
        assertEquals(OidcDiscoveryFailure.Reason.INSECURE_URL, failure.reason)

        fetcher.respondWith(wellKnown, document())
        assertEquals("$issuer/jwks", adapter.discover(issuer).getOrThrow().jwksUri)
    }

    @Test
    fun `a pinned http endpoint is refused`() {
        val overrides =
            OidcEndpointOverrides(
                authorizationEndpoint = "https://pinned.example/authorize",
                tokenEndpoint = "http://pinned.example/token",
                jwksUri = "https://pinned.example/jwks",
            )

        val result = adapter.discover(issuer, overrides)

        assertEquals(
            OidcDiscoveryFailure.Reason.INSECURE_URL,
            assertIs<OidcDiscoveryFailure>(result.exceptionOrNull()).reason,
        )
        assertEquals(0, fetcher.requestedUrls.size)
    }

    @Test
    fun `a loopback issuer may use http for local development`() {
        val local = "http://localhost:8080/realms/kotauth"
        val localWellKnown = "$local/.well-known/openid-configuration"
        fetcher.respondWith(
            localWellKnown,
            endpointDocument(
                declaredIssuer = local,
                authorizationEndpoint = "http://localhost:8080/authorize",
                tokenEndpoint = "http://127.0.0.1:8080/token",
                jwksUri = "http://localhost:8080/jwks",
            ),
        )

        val discovery = adapter.discover(local).getOrThrow()

        assertEquals(local, discovery.issuer)
        assertEquals("http://127.0.0.1:8080/token", discovery.tokenEndpoint)
    }

    @Test
    fun `a host that merely looks like loopback is refused`() {
        val lookalike = "http://localhost.attacker.example"

        val failure = assertIs<OidcDiscoveryFailure>(adapter.discover(lookalike).exceptionOrNull())

        assertEquals(OidcDiscoveryFailure.Reason.INSECURE_URL, failure.reason)
        assertEquals(0, fetcher.requestedUrls.size)
    }

    // -- response size ------------------------------------------------------

    @Test
    fun `an oversized document is refused with its own reason`() {
        fetcher.failWith = ResponseTooLargeException(1_024L, wellKnown)

        val result = adapter.discover(issuer)

        val failure = assertIs<OidcDiscoveryFailure>(result.exceptionOrNull())
        // Distinct from FETCH_FAILED on purpose: a dead IdP and an issuer URL pointing at
        // something enormous are different operator problems with different fixes.
        assertEquals(OidcDiscoveryFailure.Reason.RESPONSE_TOO_LARGE, failure.reason)
        assertNull(result.getOrNull())
    }

    private fun endpointDocument(
        declaredIssuer: String = issuer,
        authorizationEndpoint: String = "$issuer/authorize",
        tokenEndpoint: String = "$issuer/token",
        jwksUri: String = "$issuer/jwks",
    ): String =
        """
        {
          "issuer": "$declaredIssuer",
          "authorization_endpoint": "$authorizationEndpoint",
          "token_endpoint": "$tokenEndpoint",
          "jwks_uri": "$jwksUri"
        }
        """.trimIndent()
}
