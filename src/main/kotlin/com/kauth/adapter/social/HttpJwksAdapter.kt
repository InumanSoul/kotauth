package com.kauth.adapter.social

import com.kauth.domain.model.OidcUrlPolicy
import com.kauth.domain.port.JwksFailure
import com.kauth.domain.port.JwksFailure.Reason
import com.kauth.domain.port.JwksPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches an issuer's JWKS, caches its keys by kid, and bounds how often an unknown kid can
 * force a refetch.
 *
 * The refetch budget is keyed by JWKS URI, so it is spent per provider: one issuer sending
 * unknown kids cannot starve refetches for another tenant's provider, and a rotation elsewhere
 * still recovers on the next request.
 *
 * The cache is in-memory per replica, deliberately: any replica can refetch in one request, so
 * sharing it would buy a write path and a staleness question for nothing. The effective refetch
 * rate against an issuer therefore scales with replica count — accepted.
 */
class HttpJwksAdapter(
    private val fetcher: HttpJsonFetcher = JdkHttpJsonFetcher(),
    private val refetchWindowMillis: Long = DEFAULT_REFETCH_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : JwksPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    private data class Entry(
        val keys: Map<String, PublicKey>,
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
        val cached = cache[jwksUri]
        if (cached != null) {
            cached.keys[kid]?.let { return Result.success(it) }
            if (!refetchAllowed(cached, now)) {
                return failure(Reason.UNKNOWN_KID, "No key '$kid' at $jwksUri, and its refetch budget is spent.")
            }
            // Spend the budget before the attempt, so a failing issuer is not refetched on every request.
            cache[jwksUri] = cached.copy(refetchedAt = now)
        }

        val fetched = fetchKeys(jwksUri).getOrElse { return Result.failure(it) }
        cache[jwksUri] = Entry(fetched, cached?.let { now })
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
            log.warn("JWKS at {} carries no usable RSA signing key", jwksUri)
            return failure(Reason.MALFORMED, "Key set at $jwksUri carries no usable RSA signing key.")
        }
        return Result.success(keys)
    }

    /** Unusable entries are skipped rather than failing the set — issuers publish EC and enc keys too. */
    private fun parseKeys(body: String): Map<String, PublicKey> =
        json
            .parseToJsonElement(body)
            .jsonObject["keys"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { element -> rsaSigningKey(element.jsonObject) }
            .toMap()

    private fun rsaSigningKey(jwk: JsonObject): Pair<String, PublicKey>? {
        fun field(name: String): String? =
            (jwk[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        if (field("kty") != "RSA") return null
        val use = field("use")
        if (use != null && use != "sig") return null
        val kid = field("kid") ?: return null
        val modulus = field("n") ?: return null
        val exponent = field("e") ?: return null

        return runCatching {
            val spec = RSAPublicKeySpec(decodeUnsigned(modulus), decodeUnsigned(exponent))
            kid to KeyFactory.getInstance("RSA").generatePublic(spec)
        }.getOrNull()
    }

    private fun decodeUnsigned(base64Url: String): BigInteger = BigInteger(1, Base64.getUrlDecoder().decode(base64Url))

    private fun <T> failure(
        reason: Reason,
        message: String,
    ): Result<T> = Result.failure(JwksFailure(reason, message))

    companion object {
        const val DEFAULT_REFETCH_WINDOW_MILLIS: Long = 60_000L
    }
}
