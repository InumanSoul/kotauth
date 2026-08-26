package com.kauth.adapter.social

import com.kauth.adapter.token.JavaJwtVerifierAdapter
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.port.OidcEndpointOverrides
import com.kauth.domain.port.OidcRequestBinding
import com.kauth.domain.service.OidcTokenValidator
import com.kauth.domain.util.Pkce
import com.kauth.fakes.FakeOidcDiscoveryPort
import com.kauth.fakes.FakeOidcIssuer
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [OidcProviderAdapter].
 *
 * [FakeOidcIssuer] mints ID tokens with real signatures and publishes the keys that verify them,
 * and the real [OidcTokenValidator] and [JavaJwtVerifierAdapter] run behind the adapter — a faked
 * validator would accept the forged cases and prove nothing. Only the two HTTP seams are faked.
 */
class OidcProviderAdapterTest {
    private val now = Instant.parse("2026-08-25T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val issuer = FakeOidcIssuer(clock = clock)
    private val discovery = FakeOidcDiscoveryPort(issuer = issuer.issuer)
    private val poster = RecordingFormPoster()

    private val clientId = FakeOidcIssuer.DEFAULT_AUDIENCE
    private val key = requireNotNull(ProviderKey.of("okta"))

    private val binding =
        OidcRequestBinding(
            nonce = FakeOidcIssuer.DEFAULT_NONCE,
            codeVerifier = "verifier-that-began-this-request",
        )

    private fun adapter(overrides: OidcEndpointOverrides = OidcEndpointOverrides()) =
        OidcProviderAdapter(
            provider = key,
            issuer = issuer.issuer,
            scopes = listOf("openid", "email", "profile"),
            discovery = discovery,
            validator = OidcTokenValidator(issuer, JavaJwtVerifierAdapter(), clock),
            http = poster,
            overrides = overrides,
        )

    private fun authorizationUrl(overrides: OidcEndpointOverrides = OidcEndpointOverrides()) =
        adapter(overrides).buildAuthorizationUrl(
            clientId = clientId,
            redirectUri = "https://kotauth.example/t/acme/auth/social/okta/callback",
            state = "signed-state",
            scopes = emptyList(),
            binding = binding,
        )

    private fun queryOf(url: String): Map<String, String> =
        url
            .substringAfter("?")
            .split("&")
            .associate {
                val name = it.substringBefore("=")
                name to URLDecoder.decode(it.substringAfter("="), "UTF-8")
            }

    private fun exchange(idToken: String?) =
        adapter().also { poster.respondWith(tokenResponseBody(idToken)) }.exchangeCodeForProfile(
            code = "authorization-code",
            redirectUri = "https://kotauth.example/t/acme/auth/social/okta/callback",
            clientId = clientId,
            clientSecret = "client-secret",
            binding = binding,
        )

    private fun tokenResponseBody(idToken: String?): HttpJsonResponse =
        HttpJsonResponse(
            statusCode = 200,
            body =
                if (idToken == null) {
                    """{"access_token":"a","token_type":"Bearer"}"""
                } else {
                    """{"access_token":"a","token_type":"Bearer","id_token":"$idToken"}"""
                },
        )

    // -- the authorization request ------------------------------------------

    @Test
    fun `the authorization request carries the nonce, the S256 challenge and the configured scopes`() {
        val params = queryOf(authorizationUrl())

        assertEquals(binding.nonce, params["nonce"])
        assertEquals(Pkce.challengeFor(binding.codeVerifier), params["code_challenge"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("openid email profile", params["scope"])
        assertEquals("code", params["response_type"])
        assertEquals("signed-state", params["state"])
    }

    @Test
    fun `the authorization request never carries the PKCE verifier itself`() {
        // The whole point of S256: the front channel sees the hash, never the secret behind it.
        assertFalse(authorizationUrl().contains(binding.codeVerifier))
    }

    @Test
    fun `the authorization request goes to the endpoint discovery resolved for the issuer`() {
        assertTrue(authorizationUrl().startsWith("${issuer.issuer}/authorize?"))
        assertEquals(listOf(issuer.issuer), discovery.discovered)
    }

    @Test
    fun `a pinned authorization endpoint wins over the discovered one`() {
        val url = authorizationUrl(OidcEndpointOverrides(authorizationEndpoint = "https://pinned.example/authorize"))

        assertTrue(url.startsWith("https://pinned.example/authorize?"))
    }

    // -- the token exchange --------------------------------------------------

    @Test
    fun `the token exchange replays the verifier the authorization request was bound to`() {
        exchange(issuer.idToken())

        assertEquals(binding.codeVerifier, poster.lastForm["code_verifier"])
        assertEquals(
            Pkce.challengeFor(poster.lastForm.getValue("code_verifier")),
            queryOf(authorizationUrl())["code_challenge"],
            "The challenge sent at the redirect must be the S256 hash of the verifier replayed here",
        )
    }

    @Test
    fun `the token exchange authenticates with client_secret_post and asks for the authorization code grant`() {
        exchange(issuer.idToken())

        assertEquals("authorization_code", poster.lastForm["grant_type"])
        assertEquals("authorization-code", poster.lastForm["code"])
        assertEquals(clientId, poster.lastForm["client_id"])
        assertEquals("client-secret", poster.lastForm["client_secret"])
        assertEquals("${issuer.issuer}/token", poster.lastUrl)
    }

    @Test
    fun `an id token carrying the nonce we sent yields the identity from sub`() {
        val profile =
            exchange(
                issuer.idToken(
                    subject = "okta|999",
                    profile =
                        mapOf(
                            "email" to "ada@example.com",
                            "email_verified" to true,
                            "name" to "Ada Lovelace",
                            "picture" to "https://cdn.example/ada.png",
                        ),
                ),
            )

        assertEquals("okta|999", profile.providerUserId)
        assertEquals("ada@example.com", profile.email)
        assertEquals("Ada Lovelace", profile.name)
        assertEquals(true, profile.emailVerified)
        assertEquals("https://cdn.example/ada.png", profile.avatarUrl)
    }

    @Test
    fun `a missing name is composed from the name parts rather than from the email`() {
        val profile =
            exchange(
                issuer.idToken(
                    profile = mapOf("email" to "ada@example.com", "given_name" to "Ada", "family_name" to "Lovelace"),
                ),
            )

        assertEquals("Ada Lovelace", profile.name)
    }

    // -- the nonce is the one we sent ---------------------------------------

    @Test
    fun `an id token whose nonce is not the one we sent is refused`() {
        val failure =
            assertFailsWith<ProviderExchangeException> {
                exchange(issuer.idToken(nonce = "a-nonce-from-some-other-request"))
            }

        assertTrue(
            failure.message!!.contains("ID token from 'okta' was rejected"),
            "Must fail on the ID token, not on the transport: got '${failure.message}'",
        )
    }

    @Test
    fun `an id token carrying no nonce at all is refused`() {
        assertFailsWith<ProviderExchangeException> { exchange(issuer.idToken(nonce = null)) }
    }

    @Test
    fun `a refused id token is never echoed into the failure`() {
        val idToken = issuer.idToken(nonce = "a-nonce-from-some-other-request")

        val failure = assertFailsWith<ProviderExchangeException> { exchange(idToken) }

        assertFalse(failure.message!!.contains(idToken), "An ID token must never travel in an error message")
    }

    // -- transport and configuration failures --------------------------------

    @Test
    fun `a token endpoint error is refused before any signing key is fetched`() {
        poster.respondWith(HttpJsonResponse(statusCode = 400, body = """{"error":"invalid_grant"}"""))

        assertFailsWith<ProviderExchangeException> {
            adapter().exchangeCodeForProfile("code", "https://kotauth.example/cb", clientId, "secret", binding)
        }
        assertEquals(emptyList(), issuer.keyLookups, "A failed exchange must not reach the issuer's key set")
    }

    @Test
    fun `a token response with no id_token is refused`() {
        val failure = assertFailsWith<ProviderExchangeException> { exchange(null) }

        assertTrue(failure.message!!.contains("no id_token"))
    }

    @Test
    fun `a request with no binding is refused rather than sent without a nonce`() {
        val failure =
            assertFailsWith<ProviderExchangeException> {
                adapter().buildAuthorizationUrl(clientId, "https://kotauth.example/cb", "state", emptyList(), null)
            }

        assertTrue(failure.message!!.contains("nonce and a PKCE verifier"))
        assertNull(poster.lastUrl, "Nothing may be sent for a request that carries no binding")
    }

    @Test
    fun `an issuer whose endpoints cannot be resolved is refused`() {
        discovery.failWith = com.kauth.domain.port.OidcDiscoveryFailure.Reason.FETCH_FAILED

        val failure = assertFailsWith<ProviderExchangeException> { authorizationUrl() }

        assertTrue(failure.message!!.contains("endpoints for 'okta' could not be resolved"))
    }
}

/** Records what the token exchange posted and answers with a canned response. */
private class RecordingFormPoster : HttpFormPoster {
    var lastUrl: String? = null
    var lastForm: Map<String, String> = emptyMap()
    private var response: HttpJsonResponse = HttpJsonResponse(200, "{}")

    fun respondWith(response: HttpJsonResponse) {
        this.response = response
    }

    override fun post(
        url: String,
        form: Map<String, String>,
    ): HttpJsonResponse {
        lastUrl = url
        lastForm = form
        return response
    }
}
