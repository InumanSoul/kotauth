package com.kauth.fakes

import com.kauth.domain.port.OidcDiscovery
import com.kauth.domain.port.OidcDiscoveryFailure
import com.kauth.domain.port.OidcDiscoveryPort
import com.kauth.domain.port.OidcEndpointOverrides

/**
 * In-memory OidcDiscoveryPort for unit tests: it answers for one issuer with fixed endpoints,
 * applying any overrides exactly as the HTTP adapter does, and fails for every other issuer.
 */
class FakeOidcDiscoveryPort(
    private val issuer: String,
    private val authorizationEndpoint: String = "$issuer/authorize",
    private val tokenEndpoint: String = "$issuer/token",
    private val jwksUri: String = "$issuer/jwks",
) : OidcDiscoveryPort {
    /** Every issuer [discover] was asked about, in order. */
    val discovered = mutableListOf<String>()

    /** When set, every call fails with this reason instead of answering. */
    var failWith: OidcDiscoveryFailure.Reason? = null

    override fun discover(
        issuer: String,
        overrides: OidcEndpointOverrides,
    ): Result<OidcDiscovery> {
        discovered += issuer
        failWith?.let {
            return Result.failure(OidcDiscoveryFailure(it, "Discovery for $issuer failed."))
        }
        if (issuer != this.issuer) {
            return Result.failure(
                OidcDiscoveryFailure(OidcDiscoveryFailure.Reason.FETCH_FAILED, "No document at $issuer."),
            )
        }
        return Result.success(
            OidcDiscovery(
                issuer = issuer,
                authorizationEndpoint = overrides.authorizationEndpoint ?: authorizationEndpoint,
                tokenEndpoint = overrides.tokenEndpoint ?: tokenEndpoint,
                jwksUri = overrides.jwksUri ?: jwksUri,
            ),
        )
    }
}
