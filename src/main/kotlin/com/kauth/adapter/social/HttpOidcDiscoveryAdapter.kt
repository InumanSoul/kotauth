package com.kauth.adapter.social

import com.kauth.domain.model.OidcUrlPolicy
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
 * An unreachable issuer is remembered too, for [failureTtlMillis]. Without that, every request to
 * a route that discovers — `/redirect` among them, unauthenticated — fires its own outbound GET
 * and waits out the fetch timeout, so one issuer being slow or down turns this server into an
 * amplifier pointed at it.
 *
 * The cache is in-memory per replica, deliberately: any replica can refetch in one request, so
 * sharing it would buy a write path and a staleness question for nothing. The effective refetch
 * rate against an issuer therefore scales with replica count — accepted.
 */
class HttpOidcDiscoveryAdapter(
    private val fetcher: HttpJsonFetcher = JdkHttpJsonFetcher(),
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val failureTtlMillis: Long = DEFAULT_FAILURE_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : OidcDiscoveryPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    private data class Entry(
        val discovery: OidcDiscovery,
        val loadedAt: Long,
    )

    private data class Unreachable(
        val failure: OidcDiscoveryFailure,
        val at: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()
    private val unreachable = ConcurrentHashMap<String, Unreachable>()

    override fun discover(
        issuer: String,
        overrides: OidcEndpointOverrides,
    ): Result<OidcDiscovery> {
        // Defence in depth behind IdentityProviderService: a stored row can predate that check or
        // be written straight into the database. Over plaintext the issuer check below is worthless,
        // because an on-path attacker rewrites the document and its issuer field together.
        OidcUrlPolicy.problemWith(issuer, "issuer")?.let { return failure(Reason.INSECURE_URL, it) }

        pinnedDiscovery(issuer, overrides)?.let { return secured(it) }

        val now = clock()
        val cached = cache[issuer]
        if (cached != null && now - cached.loadedAt < ttlMillis) {
            return secured(cached.discovery.withOverrides(overrides))
        }

        unreachable[issuer]
            ?.takeIf { now - it.at < failureTtlMillis }
            ?.let { return Result.failure(it.failure) }

        val fetched =
            fetch(issuer).getOrElse { cause ->
                rememberIfUnreachable(issuer, cause, now)
                return Result.failure(cause)
            }
        unreachable.remove(issuer)
        cache[issuer] = Entry(fetched, now)
        return secured(fetched.withOverrides(overrides))
    }

    /**
     * Only "could not be reached" is remembered. A document we did reach and rejected is a
     * configuration problem the operator fixes and retries at once, and holding it against them
     * for half a minute buys nothing — the answer already came back fast.
     */
    private fun rememberIfUnreachable(
        issuer: String,
        cause: Throwable,
        now: Long,
    ) {
        val failure = cause as? OidcDiscoveryFailure ?: return
        if (failure.reason in UNREACHABLE_REASONS) unreachable[issuer] = Unreachable(failure, now)
    }

    /** Every endpoint we are about to hand out, pinned or discovered, must survive the URL policy. */
    private fun secured(discovery: OidcDiscovery): Result<OidcDiscovery> {
        endpointProblem(discovery)?.let { return failure(Reason.INSECURE_URL, it) }
        return Result.success(discovery)
    }

    // An http endpoint inside an https document is a downgrade nothing downstream can see.
    private fun endpointProblem(discovery: OidcDiscovery): String? =
        OidcUrlPolicy.problemWith(discovery.authorizationEndpoint, "authorization_endpoint")
            ?: OidcUrlPolicy.problemWith(discovery.tokenEndpoint, "token_endpoint")
            ?: OidcUrlPolicy.problemWith(discovery.jwksUri, "jwks_uri")

    private fun fetch(issuer: String): Result<OidcDiscovery> {
        val url = wellKnownUrl(issuer)
        val response =
            try {
                fetcher.get(url)
            } catch (e: ResponseTooLargeException) {
                log.warn("OIDC discovery fetch at {} exceeded the {} byte limit", url, e.maxBytes)
                return failure(Reason.RESPONSE_TOO_LARGE, e.message ?: "Discovery document at $url is too large.")
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

        val discovery =
            OidcDiscovery(
                issuer = declaredIssuer,
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                jwksUri = jwksUri,
                endSessionEndpoint = field("end_session_endpoint"),
            )
        // Checked here as well as in secured(), so a document publishing an http endpoint is
        // never the thing sitting in the cache for the next hour.
        endpointProblem(discovery)?.let {
            log.warn("OIDC discovery document at {} publishes a non-https endpoint", url)
            return failure(Reason.INSECURE_URL, it)
        }
        return Result.success(discovery)
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

        // Thirty seconds: long enough to collapse a flood into one outbound fetch, short enough
        // that a recovering issuer is usable again before an operator finishes reading the error.
        const val DEFAULT_FAILURE_TTL_MILLIS: Long = 30_000L

        private const val WELL_KNOWN_PATH = "/.well-known/openid-configuration"
        private val UNREACHABLE_REASONS = setOf(Reason.FETCH_FAILED, Reason.RESPONSE_TOO_LARGE)
    }
}
