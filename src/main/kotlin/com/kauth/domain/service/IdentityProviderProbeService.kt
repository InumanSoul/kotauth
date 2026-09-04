package com.kauth.domain.service

import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.port.JwksPort
import com.kauth.domain.port.OidcDiscoveryPort
import com.kauth.domain.port.OidcEndpointOverrides

/**
 * What a discovery probe resolved for one provider.
 *
 * Every field here is something the issuer published or served. Nothing about the client — its id,
 * its secret, the redirect URI it will present — can appear in this type, because none of it is
 * anything the probe touched.
 */
data class DiscoveryProbe(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val jwksUri: String,
    /** Keys usable for verification at [jwksUri], or null when the key set could not be read. */
    val verificationKeyCount: Int?,
    /** Why [verificationKeyCount] is null. */
    val keySetProblem: String?,
)

/**
 * Resolves a configured provider's issuer so an operator can see the endpoints before anyone
 * tries to sign in. It reads; it writes nothing, here or at the repository.
 *
 * It answers half of "is this provider set up correctly". The other half — whether the provider
 * will accept the redirect URI we hand it — is not observable from this side at all: the issuer's
 * document says nothing about which redirect URIs are registered against a client, and a mismatch
 * is refused at the provider, during a real sign-in, long after this returns. Callers must present
 * the result as the half it is.
 */
class IdentityProviderProbeService(
    private val discovery: OidcDiscoveryPort,
    private val jwks: JwksPort? = null,
) {
    fun probe(provider: IdentityProvider): AdminResult<DiscoveryProbe> {
        val issuer =
            provider.issuer
                ?: return AdminResult.Failure(AdminError.Validation(NO_ISSUER))

        val overrides =
            OidcEndpointOverrides(
                authorizationEndpoint = provider.authorizationEndpoint,
                tokenEndpoint = provider.tokenEndpoint,
                jwksUri = provider.jwksUri,
            )

        val document =
            discovery.discover(issuer, overrides).getOrElse { failure ->
                return AdminResult.Failure(AdminError.Validation(failure.message ?: "Discovery failed for $issuer."))
            }

        // A key set we cannot read is not a failed probe: the endpoints resolved, and saying so
        // while naming the one part that did not is more use than collapsing both into "failed".
        val keys = jwks?.verificationKeyCount(document.jwksUri)

        return AdminResult.Success(
            DiscoveryProbe(
                issuer = document.issuer,
                authorizationEndpoint = document.authorizationEndpoint,
                tokenEndpoint = document.tokenEndpoint,
                jwksUri = document.jwksUri,
                verificationKeyCount = keys?.getOrNull(),
                keySetProblem = keys?.exceptionOrNull()?.message,
            ),
        )
    }

    private companion object {
        const val NO_ISSUER = "This provider has no issuer URL, so there is no discovery document to fetch."
    }
}
