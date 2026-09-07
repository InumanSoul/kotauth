package com.kauth.adapter.social

import com.kauth.domain.port.JwksFailure
import com.kauth.fakes.FakeHttpJsonFetcher
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
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
    private val ttlMillis = 600_000L
    private val adapter =
        HttpJwksAdapter(
            fetcher,
            refetchWindowMillis = windowMillis,
            ttlMillis = ttlMillis,
            clock = { now },
        )

    private val jwksUri = "https://issuer.example/jwks"
    private val otherJwksUri = "https://other-issuer.example/jwks"

    private val firstKey = rsaKeyPair()
    private val secondKey = rsaKeyPair()
    private val p256Key = ecKeyPair("secp256r1")
    private val otherP256Key = ecKeyPair("secp256r1")
    private val p384Key = ecKeyPair("secp384r1")

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
    fun `a known kid is served from cache within the ttl and refetched after it`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        adapter.signingKey(jwksUri, "k1").getOrThrow()

        now += ttlMillis - 1
        adapter.signingKey(jwksUri, "k1").getOrThrow()
        assertEquals(1, fetcher.callCount(jwksUri))

        now += 1
        adapter.signingKey(jwksUri, "k1").getOrThrow()
        assertEquals(2, fetcher.callCount(jwksUri))
    }

    @Test
    fun `a withdrawn key stops verifying once the ttl elapses`() {
        // The kid stays known, so nothing about it ever triggers the unknown-kid refetch. Only the
        // TTL sends us back to the issuer, and only then does the withdrawal take effect.
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        assertEquals(firstKey, adapter.signingKey(jwksUri, "k1").getOrThrow())

        fetcher.respondWith(jwksUri, keySet("k2" to secondKey))
        now += ttlMillis - 1
        assertEquals(
            firstKey,
            adapter.signingKey(jwksUri, "k1").getOrThrow(),
            "Inside the TTL the cached key is still served — that is the window the TTL bounds",
        )

        now += 1
        val result = adapter.signingKey(jwksUri, "k1")

        assertEquals(JwksFailure.Reason.UNKNOWN_KID, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull(), "A key the issuer no longer publishes must not verify anything")
    }

    @Test
    fun `an expired entry that fails to refetch is not served from the stale cache`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        adapter.signingKey(jwksUri, "k1").getOrThrow()

        fetcher.shouldFail = true
        now += ttlMillis

        val result = adapter.signingKey(jwksUri, "k1")

        // Fail closed: falling back to the stale entry would hand out exactly the key the TTL
        // exists to stop handing out, and an unreachable issuer is the easy way to arrange that.
        assertEquals(JwksFailure.Reason.FETCH_FAILED, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull())
    }

    @Test
    fun `an expired entry comes back with a fresh refetch budget`() {
        fetcher.respondWith(jwksUri, keySet("k1" to firstKey))
        adapter.signingKey(jwksUri, "k1").getOrThrow()
        adapter.signingKey(jwksUri, "unknown-1")
        adapter.signingKey(jwksUri, "unknown-2")
        assertEquals(2, fetcher.callCount(jwksUri))

        // Past the TTL the entry is gone, so this is an initial load, not a budgeted refetch.
        now += ttlMillis
        adapter.signingKey(jwksUri, "k1").getOrThrow()
        adapter.signingKey(jwksUri, "unknown-3")

        assertEquals(4, fetcher.callCount(jwksUri))
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
    fun `a key set of only unsupported key types is rejected`() {
        fetcher.respondWith(jwksUri, """{"keys":[{"kty":"oct","kid":"sym-1","k":"c2VjcmV0"}]}""")

        val result = adapter.signingKey(jwksUri, "sym-1")

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

    // -- key types ----------------------------------------------------------

    @Test
    fun `an ec p-256 key resolves to its signing key`() {
        fetcher.respondWith(jwksUri, keySetOf(ecJwk("ec-1", p256Key)))

        val key = adapter.signingKey(jwksUri, "ec-1").getOrThrow()

        assertEquals(p256Key, key)
        // The verifier casts to ECPublicKey for ES256; a key of any other type would be a mismatch.
        assertIs<ECPublicKey>(key)
    }

    @Test
    fun `an rsa key and an ec key live in the same set`() {
        fetcher.respondWith(jwksUri, keySetOf(jwk("k1", firstKey), ecJwk("ec-1", p256Key)))

        assertEquals(firstKey, adapter.signingKey(jwksUri, "k1").getOrThrow())
        assertEquals(p256Key, adapter.signingKey(jwksUri, "ec-1").getOrThrow())
        assertEquals(1, fetcher.callCount(jwksUri))
    }

    @Test
    fun `an ec key on an unsupported curve is skipped rather than failing the set`() {
        fetcher.respondWith(jwksUri, keySetOf(jwk("k1", firstKey), ecJwk("ec-384", p384Key, curve = "P-384")))

        // The RSA key beside it still resolves — an entry we cannot use is skipped, not fatal.
        assertEquals(firstKey, adapter.signingKey(jwksUri, "k1").getOrThrow())

        val result = adapter.signingKey(jwksUri, "ec-384")
        assertEquals(JwksFailure.Reason.UNKNOWN_KID, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull())
    }

    @Test
    fun `a set of only an unsupported curve is unusable`() {
        // P-384 pairs with ES384, which the validator does not allow. Accepting it here would hand
        // the verifier a key for an algorithm it will refuse.
        fetcher.respondWith(jwksUri, keySetOf(ecJwk("ec-384", p384Key, curve = "P-384")))

        val result = adapter.signingKey(jwksUri, "ec-384")

        assertEquals(JwksFailure.Reason.MALFORMED, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull())
    }

    @Test
    fun `a key claiming an unsupported curve is refused even when its coordinates would parse`() {
        // The crv label is the only thing separating this from a usable P-256 key: the coordinates
        // are P-256 sized, so the octet-length guard never sees it. An issuer that mislabels — or a
        // document that lies — must not get a key built for a curve the validator will not accept.
        val lying = ecJwk("ec-lying", p256Key, curve = "P-384", octets = P256_OCTETS)
        fetcher.respondWith(jwksUri, keySetOf(lying))

        val result = adapter.signingKey(jwksUri, "ec-lying")

        assertEquals(JwksFailure.Reason.MALFORMED, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull())
    }

    @Test
    fun `a key claiming p-256 with oversized coordinates is refused`() {
        val oversized = ecJwk("ec-fat", p384Key, curve = "P-256", octets = P384_OCTETS)
        fetcher.respondWith(jwksUri, keySetOf(oversized))

        val result = adapter.signingKey(jwksUri, "ec-fat")

        assertEquals(JwksFailure.Reason.MALFORMED, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertNull(result.getOrNull())
    }

    @Test
    fun `an encryption-only ec key is not offered as a signing key`() {
        fetcher.respondWith(
            jwksUri,
            keySetOf(ecJwk("ec-enc", p256Key, use = "enc"), ecJwk("ec-sig", otherP256Key)),
        )

        val result = adapter.signingKey(jwksUri, "ec-enc")

        assertEquals(JwksFailure.Reason.UNKNOWN_KID, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertEquals(otherP256Key, adapter.signingKey(jwksUri, "ec-sig").getOrThrow())
    }

    @Test
    fun `a key whose key_ops exclude verify is not offered as a signing key`() {
        fetcher.respondWith(
            jwksUri,
            keySetOf(
                jwk("k-encrypt", firstKey, keyOps = listOf("encrypt", "wrapKey")),
                jwk("k-verify", secondKey, keyOps = listOf("verify")),
            ),
        )

        val result = adapter.signingKey(jwksUri, "k-encrypt")

        assertEquals(JwksFailure.Reason.UNKNOWN_KID, assertIs<JwksFailure>(result.exceptionOrNull()).reason)
        assertEquals(secondKey, adapter.signingKey(jwksUri, "k-verify").getOrThrow())
    }

    // -- helpers ------------------------------------------------------------

    private fun rsaKeyPair(): RSAPublicKey =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .public as RSAPublicKey

    private fun ecKeyPair(curve: String): ECPublicKey =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec(curve)) }
            .generateKeyPair()
            .public as ECPublicKey

    private fun keySet(vararg keys: Pair<String, RSAPublicKey>): String =
        keySetOf(*keys.map { (kid, key) -> jwk(kid, key) }.toTypedArray())

    private fun keySetOf(vararg entries: String): String = """{"keys":[${entries.joinToString(",")}]}"""

    private fun jwk(
        kid: String,
        key: RSAPublicKey,
        use: String = "sig",
        keyOps: List<String>? = null,
    ): String =
        """{"kty":"RSA","use":"$use","alg":"RS256","kid":"$kid"${keyOpsField(keyOps)},""" +
            """"n":"${base64Url(key.modulus)}","e":"${base64Url(key.publicExponent)}"}"""

    private fun ecJwk(
        kid: String,
        key: ECPublicKey,
        curve: String = "P-256",
        use: String = "sig",
        octets: Int = if (curve == "P-256") P256_OCTETS else P384_OCTETS,
    ): String =
        """{"kty":"EC","use":"$use","crv":"$curve","kid":"$kid",""" +
            """"x":"${base64UrlFixed(key.w.affineX, octets)}","y":"${base64UrlFixed(key.w.affineY, octets)}"}"""

    private fun keyOpsField(keyOps: List<String>?): String =
        keyOps?.joinToString(",") { """"$it"""" }?.let { ""","key_ops":[$it]""" } ?: ""

    /** RFC 7518 6.2.1.2 — coordinates are left-padded to the curve's fixed octet length. */
    private fun base64UrlFixed(
        value: BigInteger,
        octets: Int,
    ): String {
        val raw = unsigned(value)
        val padded = ByteArray(octets)
        raw.copyInto(padded, octets - raw.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(padded)
    }

    private fun base64Url(value: BigInteger): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(value))

    private fun unsigned(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    private companion object {
        const val P256_OCTETS = 32
        const val P384_OCTETS = 48
    }
}
