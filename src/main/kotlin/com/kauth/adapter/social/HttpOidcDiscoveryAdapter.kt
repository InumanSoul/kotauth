package com.kauth.adapter.social

import com.kauth.domain.port.OidcDiscovery
import com.kauth.domain.port.OidcDiscoveryFailure
import com.kauth.domain.port.OidcDiscoveryFailure.Reason
import com.kauth.domain.port.OidcDiscoveryPort
import com.kauth.domain.port.OidcEndpointOverrides
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches an issuer's OIDC discovery document, proves it belongs to that issuer, and caches it.
 *
 * The cache is in-memory per replica, deliberately: any replica can refetch in one request, so
 * sharing it would buy a write path and a staleness question for nothing. The effective refetch
 * rate against an issuer therefore scales with replica count — accepted.
 */
class HttpOidcDiscoveryAdapter(
    private val fetcher: HttpJsonFetcher = JdkHttpJsonFetcher(),
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : OidcDiscoveryPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    private data class Entry(
        val discovery: OidcDiscovery,
        val loadedAt: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun discover(
        issuer: String,
        overrides: OidcEndpointOverrides,
    ): Result<OidcDiscovery> {
        pinnedDiscovery(issuer, overrides)?.let { return Result.success(it) }

        val now = clock()
        val cached = cache[issuer]
        if (cached != null && now - cached.loadedAt < ttlMillis) {
            return Result.success(cached.discovery.withOverrides(overrides))
        }

        val fetched = fetch(issuer).getOrElse { return Result.failure(it) }
        cache[issuer] = Entry(fetched, now)
        return Result.success(fetched.withOverrides(overrides))
    }

    private fun fetch(issuer: String): Result<OidcDiscovery> {
        val url = wellKnownUrl(issuer)
        val response =
            try {
                fetcher.get(url)
            } catch (e: Exception) {
                log.warn("OIDC discovery fetch failed for {}: {}", url, e.javaClass.simpleName)
                return failure(Reason.FETCH_FAILED, "Could not reach the discovery document at $url.")
            }
        if (response.statusCode != 200) {
            log.warn("OIDC discovery fetch returned HTTP {} for {}", response.statusCode, url)
            return failure(Reason.FETCH_FAILED, "Discovery document at $url returned HTTP ${response.statusCode}.")
        }

        val document =
            try {
                json.parseToJsonElement(response.body).jsonObject
            } catch (e: Exception) {
                log.warn("OIDC discovery document at {} did not parse: {}", url, e.javaClass.simpleName)
                return failure(Reason.MALFORMED, "Discovery document at $url is not a JSON object.")
            }

        fun field(name: String): String? =
            (document[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        val declaredIssuer =
            field("issuer")
                ?: return failure(Reason.MALFORMED, "Discovery document at $url declares no issuer.")

        // The check the rest of the phase rests on. Anything able to redirect this fetch — a hostile
        // document, a compromised CDN, DNS — otherwise chooses our authorization endpoint and our
        // jwks_uri, and every later validation then passes against the attacker's own key.
        if (declaredIssuer != issuer) {
            log.warn("OIDC discovery issuer mismatch at {}: declared {}, expected {}", url, declaredIssuer, issuer)
            return failure(
                Reason.ISSUER_MISMATCH,
                "Discovery document at $url declares issuer '$declaredIssuer', expected '$issuer'.",
            )
        }

        val authorizationEndpoint =
            field("authorization_endpoint")
                ?: return failure(Reason.MALFORMED, "Discovery document at $url declares no authorization_endpoint.")
        val tokenEndpoint =
            field("token_endpoint")
                ?: return failure(Reason.MALFORMED, "Discovery document at $url declares no token_endpoint.")
        val jwksUri =
            field("jwks_uri")
                ?: return failure(Reason.MALFORMED, "Discovery document at $url declares no jwks_uri.")

        return Result.success(
            OidcDiscovery(
                issuer = declaredIssuer,
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                jwksUri = jwksUri,
                endSessionEndpoint = field("end_session_endpoint"),
            ),
        )
    }

    /** A complete set of pinned endpoints needs no document at all. */
    private fun pinnedDiscovery(
        issuer: String,
        overrides: OidcEndpointOverrides,
    ): OidcDiscovery? {
        val authorization = overrides.authorizationEndpoint.pinned() ?: return null
        val token = overrides.tokenEndpoint.pinned() ?: return null
        val jwks = overrides.jwksUri.pinned() ?: return null
        return OidcDiscovery(issuer, authorization, token, jwks)
    }

    private fun OidcDiscovery.withOverrides(overrides: OidcEndpointOverrides): OidcDiscovery =
        copy(
            authorizationEndpoint = overrides.authorizationEndpoint.pinned() ?: authorizationEndpoint,
            tokenEndpoint = overrides.tokenEndpoint.pinned() ?: tokenEndpoint,
            jwksUri = overrides.jwksUri.pinned() ?: jwksUri,
        )

    private fun String?.pinned(): String? = this?.takeIf { it.isNotBlank() }

    private fun wellKnownUrl(issuer: String): String = "${issuer.trimEnd('/')}$WELL_KNOWN_PATH"

    private fun failure(
        reason: Reason,
        message: String,
    ): Result<OidcDiscovery> = Result.failure(OidcDiscoveryFailure(reason, message))

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 3_600_000L
        private const val WELL_KNOWN_PATH = "/.well-known/openid-configuration"
    }
}
