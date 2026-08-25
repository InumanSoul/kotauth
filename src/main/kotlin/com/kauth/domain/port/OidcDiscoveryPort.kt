package com.kauth.domain.port

/**
 * The endpoints an OIDC issuer publishes, once the document has been proven to belong to it.
 */
data class OidcDiscovery(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val jwksUri: String,
    /** Captured so RP-initiated logout is later a wiring change, not a schema change. Unread today. */
    val endSessionEndpoint: String? = null,
)

/**
 * Endpoints an operator pinned on the provider row. Each one supplied wins over the document,
 * and a complete set answers without any fetch at all — the escape hatch for an issuer whose
 * discovery document is absent or wrong.
 */
data class OidcEndpointOverrides(
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val jwksUri: String? = null,
)

/** A discovery failure. Carried in `Result.failure`; never thrown across the port boundary. */
class OidcDiscoveryFailure(
    val reason: Reason,
    message: String,
) : Exception(message) {
    enum class Reason {
        /** The document could not be retrieved. */
        FETCH_FAILED,

        /** The document parsed but is missing an endpoint we require. */
        MALFORMED,

        /** The document names an issuer other than the configured one. */
        ISSUER_MISMATCH,
    }
}

/** Outbound port — resolve an issuer URL into the endpoints needed to talk to it. */
interface OidcDiscoveryPort {
    /**
     * Resolves [issuer] to its endpoints, honouring any [overrides] the operator pinned.
     *
     * The returned document is only ever one whose own `issuer` matched [issuer] exactly.
     */
    fun discover(
        issuer: String,
        overrides: OidcEndpointOverrides = OidcEndpointOverrides(),
    ): Result<OidcDiscovery>
}
