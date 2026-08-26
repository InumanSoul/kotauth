package com.kauth.adapter.social

import com.kauth.domain.port.JwksFailure
import com.kauth.fakes.FakeHttpJsonFetcher
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [HttpJwksAdapter]. The suite has no network, so every fetch goes through
 * [FakeHttpJsonFetcher] — which also counts the fetches the refetch limiter is there to bound.
 */
class HttpJwksAdapterTest {
    private val fetcher = FakeHttpJsonFetcher()
    private var now = 0L
    private val windowMillis = 60_000L
    private val adapter = HttpJwksAdapter(fetcher, refetchWindowMillis = windowMillis, clock = { now })

    private val jwksUri = "https://issuer.example/jwks"
    private val otherJwksUri = "https://other-issuer.example/jwks"

    private val firstKey = rsaKeyPair()
    private val secondKey = rsaKeyPair()

    @BeforeTest
    fun setUp() {
        fetcher.clear()
        now = 0L
    }

    @Test
    fun `a known kid resolves to its signing key and is then served from cache`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))

        assertEquals(firstKey, adapter.signingKey(jwksUri, "k1").getOrThrow())
        adapter.signingKey(jwksUri, "k1").getOrThrow()

        assertEquals(1, fetcher.callCount(jwksUri))
    }

    @Test
    fun `an unknown kid triggers exactly one refetch`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        adapter.signingKey(jwksUri, "k1").getOrThrow()

        val result = adapter.signingKey(jwksUri, "rotated")

        assertEquals(2, fetcher.callCount(jwksUri))
        assertEquals(JwksFailure.Reason.UNKNOWN_KID, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull())
    }

    @Test
    fun `a rotated key is picked up by that refetch`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        adapter.signingKey(jwksUri, "k1").getOrThrow()

        fetcher.respondWith(jwksUri, keySet("k1" to firstKey, "k2" to secondKey))

        assertEquals(secondKey, adapter.signingKey(jwksUri, "k2").getOrThrow())
        assertEquals(2, fetcher.callCount(jwksUri))
    }

    @Test
    fun `a second unknown kid within the window does not refetch`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        adapter.signingKey(jwksUri, "k1").getOrThrow()
        adapter.signingKey(jwksUri, "unknown-1")
        assertEquals(2, fetcher.callCount(jwksUri))

        now += windowMillis - 1
        val result = adapter.signingKey(jwksUri, "unknown-2")

        assertEquals(2, fetcher.callCount(jwksUri))
        assertEquals(JwksFailure.Reason.UNKNOWN_KID, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
    }

    @Test
    fun `the refetch budget reopens once the window elapses`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        adapter.signingKey(jwksUri, "k1").getOrThrow()
        adapter.signingKey(jwksUri, "unknown-1")

        now += windowMillis
        adapter.signingKey(jwksUri, "unknown-2")

        assertEquals(3, fetcher.callCount(jwksUri))
    }

    @Test
    fun `the refetch budget is spent per provider, not globally`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        fetcher.respondWith(otherJwksUri, keySet("other-k1" to secondKey))

        // Exhaust the first provider's budget: one initial fetch, one refetch, then throttled.
        adapter.signingKey(jwksUri, "k1").getOrThrow()
        adapter.signingKey(jwksUri, "unknown-1")
        adapter.signingKey(jwksUri, "unknown-2")
        assertEquals(2, fetcher.callCount(jwksUri))

        // A second provider must still get its own refetch. Under a limiter shared across
        // providers this call is throttled and the count stays at 1 — which is the whole point.
        adapter.signingKey(otherJwksUri, "other-k1").getOrThrow()
        val result = adapter.signingKey(otherJwksUri, "unknown-1")

        assertEquals(2, fetcher.callCount(otherJwksUri))
        assertEquals(2, fetcher.callCount(jwksUri))
        assertEquals(JwksFailure.Reason.UNKNOWN_KID, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
    }

    @Test
    fun `a throttled provider keeps serving the keys it already holds`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        adapter.signingKey(jwksUri, "k1").getOrThrow()
        adapter.signingKey(jwksUri, "unknown-1")

        assertEquals(firstKey, adapter.signingKey(jwksUri, "k1").getOrThrow())
        assertEquals(2, fetcher.callCount(jwksUri))
    }

    @Test
    fun `a transport failure returns a failure and never a key`() {
        fetcher.shouldFail = true

        val result = adapter.signingKey(jwksUri, "k1")

        assertEquals(JwksFailure.Reason.FETCH_FAILED, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull())
    }

    @Test
    fun `a failed refetch leaves the cached keys intact and spends the budget`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        adapter.signingKey(jwksUri, "k1").getOrThrow()

        fetcher.shouldFail = true
        assertTrue(adapter.signingKey(jwksUri, "unknown-1").isFailure)
        assertTrue(adapter.signingKey(jwksUri, "unknown-2").isFailure)
        assertEquals(2, fetcher.callCount(jwksUri))

        fetcher.shouldFail = false
        assertEquals(firstKey, adapter.signingKey(jwksUri, "k1").getOrThrow())
    }

    @Test
    fun `a non-200 response returns a failure`() {
        fetcher.respondWith(jwksUri, "nope", statusCode = 500)

        val result = adapter.signingKey(jwksUri, "k1")

        assertEquals(JwksFailure.Reason.FETCH_FAILED, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
    }

    @Test
    fun `a key set with no usable rsa signing key is rejected`() {
        fetcher.respondWith(jwksUri, """{"keys":[{"kty":"EC","kid":"ec-1","crv":"P-256","x":"a","y":"b"}]}""")

        val result = adapter.signingKey(jwksUri, "ec-1")

        assertEquals(JwksFailure.Reason.MALFORMED, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull())
    }

    @Test
    fun `an encryption-only key is not offered as a signing key`() {
        val encOnly =
            """{"keys":[${jwk("k1", firstKey, use = "enc")},${jwk("k2", secondKey, use = "sig")}]}"""
        fetcher.respondWith(jwksUri, encOnly)

        val encResult = adapter.signingKey(jwksUri, "k1")
        assertEquals(JwksFailure.Reason.UNKNOWN_KID, assertIs<JwksFailure>(encResult.exceptionOrNull()).reason)
        assertEquals(secondKey, adapter.signingKey(jwksUri, "k2").getOrThrow())
    }

    // -- scheme enforcement and response size -------------------------------

    @Test
    fun `an http jwks uri is refused before any fetch is made`() {
        val insecure = "http://issuer.example/jwks"
        fetcher.respondWith(insecure, keySet("k1" to firstKey))

        val result = adapter.signingKey(insecure, "k1")

        assertEquals(JwksFailure.Reason.INSECURE_URL, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull())
        assertEquals(0, fetcher.requestedUrls.size)
    }

    @Test
    fun `a loopback jwks uri may use http for local development`() {
        val local = "http://localhost:8080/jwks"
        fetcher.respondWith(local, keySet("k1" to firstKey))

        assertEquals(firstKey, adapter.signingKey(local, "k1").getOrThrow())
    }

    @Test
    fun `a host that merely looks like loopback is refused`() {
        val lookalike = "http://127.0.0.1.attacker.example/jwks"
        fetcher.respondWith(lookalike, keySet("k1" to firstKey))

        val result = adapter.signingKey(lookalike, "k1")

        assertEquals(JwksFailure.Reason.INSECURE_URL, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertEquals(0, fetcher.requestedUrls.size)
    }

    @Test
    fun `an oversized key set is refused with its own reason`() {
        fetcher.failWith = ResponseTooLargeException(1_024L, jwksUri)

        val result = adapter.signingKey(jwksUri, "k1")

        assertEquals(JwksFailure.Reason.RESPONSE_TOO_LARGE, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull())
    }

    // -- helpers ------------------------------------------------------------

    private fun rsaKeyPair(): RSAPublicKey =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .public as RSAPublicKey

    private fun keySet(vararg keys: Pair<String, RSAPublicKey>): String =
        """{"keys":[${keys.joinToString(",") { (kid, key) -> jwk(kid, key) }}]}"""

    private fun jwk(
        kid: String,
        key: RSAPublicKey,
        use: String = "sig",
    ): String =
        """{"kty":"RSA","use":"$use","alg":"RS256","kid":"$kid",""" +
            """"n":"${base64Url(key.modulus)}","e":"${base64Url(key.publicExponent)}"}"""

    private fun base64Url(value: BigInteger): String {
        val raw = value.toByteArray()
        val bytes = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
