package com.kauth.adapter.social

import com.kauth.domain.model.OidcUrlPolicy
import com.kauth.domain.port.JwksFailure
import com.kauth.domain.port.JwksFailure.Reason
import com.kauth.domain.port.JwksPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches an issuer's JWKS, caches its keys by kid for [ttlMillis], and bounds how often an
 * unknown kid can force a refetch.
 *
 * The TTL is what bounds a *revoked* key. An unknown kid is not the only thing that should send
 * us back to the issuer: when a key is compromised and withdrawn, its kid stays perfectly well
 * known, so refetch-on-unknown-kid alone would serve the withdrawn key forever and every forgery
 * made with it would verify.
 *
 * The refetch budget is keyed by JWKS URI, so it is spent per key set, not globally: one issuer
 * sending unknown kids cannot starve refetches for a *different* issuer, and a rotation there
 * still recovers on the next request. Two tenants pointed at the same issuer share one budget —
 * per URI is not per tenant, and either of them can spend it for both.
 *
 * The cache is in-memory per replica, deliberately: any replica can refetch in one request, so
 * sharing it would buy a write path and a staleness question for nothing. The effective refetch
 * rate against an issuer therefore scales with replica count — accepted.
 */
class HttpJwksAdapter(
    private val fetcher: HttpJsonFetcher = JdkHttpJsonFetcher(),
    private val refetchWindowMillis: Long = DEFAULT_REFETCH_WINDOW_MILLIS,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : JwksPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    private data class Entry(
        val keys: Map<String, PublicKey>,
        /** When these keys came off the wire. Past [ttlMillis] the entry is not served at all. */
        val loadedAt: Long,
        /** When this URI last spent its refetch budget; null while only the initial load has run. */
        val refetchedAt: Long?,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun signingKey(
        jwksUri: String,
        kid: String,
    ): Result<PublicKey> {
        // Third gate on the same rule, after config time and discovery: no path reaches a signing
        // key over plaintext, including a jwks_uri written straight into the database.
        OidcUrlPolicy.problemWith(jwksUri, "JWKS URI")?.let { return failure(Reason.INSECURE_URL, it) }

        val now = clock()
        // An expired entry is treated as absent rather than refreshed in place: a fresh load is a
        // fresh budget, and until it lands nothing stale is handed out.
        val cached = cache[jwksUri]?.takeIf { now - it.loadedAt < ttlMillis }
        if (cached != null) {
            cached.keys[kid]?.let { return Result.success(it) }
            if (!refetchAllowed(cached, now)) {
                return failure(Reason.UNKNOWN_KID, "No key '$kid' at $jwksUri, and its refetch budget is spent.")
            }
            // Spend the budget before the attempt, so a failing issuer is not refetched on every request.
            cache[jwksUri] = cached.copy(refetchedAt = now)
        }

        val fetched = fetchKeys(jwksUri).getOrElse { return Result.failure(it) }
        cache[jwksUri] = Entry(fetched, loadedAt = now, refetchedAt = cached?.let { now })
        return fetched[kid]?.let { Result.success(it) }
            ?: failure(Reason.UNKNOWN_KID, "No key '$kid' in the key set at $jwksUri.")
    }

    private fun refetchAllowed(
        entry: Entry,
        now: Long,
    ): Boolean = entry.refetchedAt == null || now - entry.refetchedAt >= refetchWindowMillis

    private fun fetchKeys(jwksUri: String): Result<Map<String, PublicKey>> {
        val response =
            try {
                fetcher.get(jwksUri)
            } catch (e: ResponseTooLargeException) {
                log.warn("JWKS fetch at {} exceeded the {} byte limit", jwksUri, e.maxBytes)
                return failure(Reason.RESPONSE_TOO_LARGE, e.message ?: "Key set at $jwksUri is too large.")
            } catch (e: Exception) {
                log.warn("JWKS fetch failed for {}: {}", jwksUri, e.javaClass.simpleName)
                return failure(Reason.FETCH_FAILED, "Could not reach the key set at $jwksUri.")
            }
        if (response.statusCode != 200) {
            log.warn("JWKS fetch returned HTTP {} for {}", response.statusCode, jwksUri)
            return failure(Reason.FETCH_FAILED, "Key set at $jwksUri returned HTTP ${response.statusCode}.")
        }

        val keys =
            try {
                parseKeys(response.body)
            } catch (e: Exception) {
                log.warn("JWKS at {} did not parse: {}", jwksUri, e.javaClass.simpleName)
                return failure(Reason.MALFORMED, "Key set at $jwksUri is not a JWK Set.")
            }
        if (keys.isEmpty()) {
            log.warn("JWKS at {} carries no usable signing key", jwksUri)
            return failure(Reason.MALFORMED, "Key set at $jwksUri carries no usable signing key.")
        }
        return Result.success(keys)
    }

    /**
     * Unusable entries are skipped rather than failing the set — issuers publish encryption keys,
     * unsupported curves and key types we cannot verify with, alongside the ones we can.
     */
    private fun parseKeys(body: String): Map<String, PublicKey> =
        json
            .parseToJsonElement(body)
            .jsonObject["keys"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { element -> signingKeyOf(element.jsonObject) }
            .toMap()

    private fun signingKeyOf(jwk: JsonObject): Pair<String, PublicKey>? {
        if (!jwk.usableForVerification()) return null
        val kid = jwk.string("kid") ?: return null
        val key =
            when (jwk.string("kty")) {
                "RSA" -> rsaKey(jwk)
                "EC" -> ecKey(jwk)
                else -> null
            }
        return key?.let { kid to it }
    }

    // RFC 7517 4.2 and 4.3 — a key published for encryption is not a signing key, whatever its type.
    private fun JsonObject.usableForVerification(): Boolean {
        val use = string("use")
        if (use != null && use != "sig") return false
        val keyOps =
            (this["key_ops"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        return keyOps == null || "verify" in keyOps
    }

    private fun rsaKey(jwk: JsonObject): PublicKey? {
        val modulus = jwk.string("n") ?: return null
        val exponent = jwk.string("e") ?: return null
        return runCatching {
            val spec = RSAPublicKeySpec(decodeUnsigned(modulus), decodeUnsigned(exponent))
            KeyFactory.getInstance("RSA").generatePublic(spec)
        }.getOrNull()
    }

    // P-256 only. P-384 and P-521 pair with ES384 and ES512, which OidcTokenValidator does not
    // allow, so accepting them here would rebuild the same allowlist mismatch one curve over.
    private fun ecKey(jwk: JsonObject): PublicKey? {
        if (jwk.string("crv") != P256_CURVE) return null
        val x = jwk.string("x") ?: return null
        val y = jwk.string("y") ?: return null
        return runCatching {
            val point = ECPoint(decodeCoordinate(x), decodeCoordinate(y))
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, p256Parameters))
        }.getOrNull()
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun decodeUnsigned(base64Url: String): BigInteger = BigInteger(1, Base64.getUrlDecoder().decode(base64Url))

    // RFC 7518 6.2.1.2 — a P-256 coordinate is 32 octets. The JDK's EC KeyFactory rejects an
    // out-of-field coordinate on its own, so this guard is an explicit, provider-independent
    // restatement rather than the only thing refusing one; verified by mutation, which it survives.
    private fun decodeCoordinate(base64Url: String): BigInteger {
        val bytes = Base64.getUrlDecoder().decode(base64Url)
        require(bytes.size <= P256_COORDINATE_BYTES) { "not a P-256 coordinate" }
        return BigInteger(1, bytes)
    }

    private val p256Parameters: ECParameterSpec by lazy {
        AlgorithmParameters
            .getInstance("EC")
            .apply { init(ECGenParameterSpec(P256_STANDARD_NAME)) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    private fun <T> failure(
        reason: Reason,
        message: String,
    ): Result<T> = Result.failure(JwksFailure(reason, message))

    companion object {
        const val DEFAULT_REFETCH_WINDOW_MILLIS: Long = 60_000L

        // Ten minutes: the midpoint of what mainstream JWKS clients use, and the window a
        // withdrawn key stays forgeable for. Shorter multiplies outbound fetches per replica for
        // little more safety; longer leaves a compromised key live for a quarter of an hour.
        const val DEFAULT_TTL_MILLIS: Long = 600_000L
        private const val P256_CURVE = "P-256"
        private const val P256_STANDARD_NAME = "secp256r1"
        private const val P256_COORDINATE_BYTES = 32
    }
}
