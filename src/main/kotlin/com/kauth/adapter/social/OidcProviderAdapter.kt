package com.kauth.adapter.social

import com.kauth.domain.model.ProviderKey
import com.kauth.domain.port.OidcDiscovery
import com.kauth.domain.port.OidcDiscoveryPort
import com.kauth.domain.port.OidcEndpointOverrides
import com.kauth.domain.port.OidcRequestBinding
import com.kauth.domain.port.SocialProviderPort
import com.kauth.domain.port.SocialUserProfile
import com.kauth.domain.service.OidcClaims
import com.kauth.domain.service.OidcTokenValidator
import com.kauth.domain.util.Pkce
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * A [SocialProviderPort] over a generic OIDC issuer, built from one tenant's provider row rather
 * than from compiled-in endpoints.
 *
 * The identity it returns is the ID token's `sub`. Nothing is read from the token endpoint's
 * response beyond `id_token`, and no userinfo request is made: the ID token is signed by the
 * issuer and validated in full by [OidcTokenValidator], so it is the one part of the exchange
 * whose provenance is proven.
 *
 * Never log: the authorization code, the client secret, the PKCE verifier, or the ID token.
 */
class OidcProviderAdapter(
    override val provider: ProviderKey,
    private val issuer: String,
    private val scopes: List<String>,
    private val discovery: OidcDiscoveryPort,
    private val validator: OidcTokenValidator,
    private val http: HttpFormPoster,
    private val overrides: OidcEndpointOverrides = OidcEndpointOverrides(),
) : SocialProviderPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    override fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        scopes: List<String>,
        binding: OidcRequestBinding?,
    ): String {
        val bound = requireBinding(binding)
        val effectiveScopes = scopes.ifEmpty { this.scopes }
        val params =
            mapOf(
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to effectiveScopes.joinToString(" "),
                "state" to state,
                "nonce" to bound.nonce,
                // Derived here and nowhere else, from the verifier that rides in the same state.
                "code_challenge" to Pkce.challengeFor(bound.codeVerifier),
                "code_challenge_method" to Pkce.CHALLENGE_METHOD_S256,
            )
        return "${endpoints().authorizationEndpoint}?${params.toQueryString()}"
    }

    override fun exchangeCodeForProfile(
        code: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String,
        binding: OidcRequestBinding?,
    ): SocialUserProfile {
        val bound = requireBinding(binding)
        val endpoints = endpoints()
        val idToken = requestIdToken(endpoints.tokenEndpoint, code, redirectUri, clientId, clientSecret, bound)
        val claims =
            validator
                .validate(
                    idToken = idToken,
                    expectedIssuer = issuer,
                    clientId = clientId,
                    // The nonce we sent, recovered from the signed state — never one minted here,
                    // which would compare a fresh value against itself and prove nothing.
                    expectedNonce = bound.nonce,
                    jwksUri = endpoints.jwksUri,
                ).getOrElse { cause ->
                    log.warn("ID token from {} rejected: {}", provider.value, cause.message)
                    throw ProviderExchangeException("The ID token from '${provider.value}' was rejected.")
                }
        return claims.toProfile()
    }

    private fun requestIdToken(
        tokenEndpoint: String,
        code: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String,
        binding: OidcRequestBinding,
    ): String {
        val form =
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                // client_secret_post: the one client authentication method every issuer accepts.
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "code_verifier" to binding.codeVerifier,
            )
        val response =
            try {
                http.post(tokenEndpoint, form)
            } catch (e: Exception) {
                log.warn("Token exchange with {} failed: {}", provider.value, e.javaClass.simpleName)
                throw ProviderExchangeException("The token endpoint for '${provider.value}' could not be reached.")
            }
        if (response.statusCode != HTTP_OK) {
            // The body of a failed token response can echo the code back; only the status is safe.
            log.warn("Token exchange with {} returned HTTP {}", provider.value, response.statusCode)
            throw ProviderExchangeException("The token endpoint for '${provider.value}' returned an error.")
        }
        return runCatching {
            json
                .parseToJsonElement(response.body)
                .jsonObject["id_token"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()
            ?: throw ProviderExchangeException("The token response from '${provider.value}' carried no id_token.")
    }

    private fun endpoints(): OidcDiscovery =
        discovery.discover(issuer, overrides).getOrElse { cause ->
            log.warn("Discovery for {} failed: {}", provider.value, cause.message)
            throw ProviderExchangeException("The endpoints for '${provider.value}' could not be resolved.")
        }

    private fun requireBinding(binding: OidcRequestBinding?): OidcRequestBinding =
        binding ?: throw ProviderExchangeException("An OIDC request must carry a nonce and a PKCE verifier.")

    /**
     * `sub` is the identity. A missing `name` is composed from the parts rather than falling back
     * to the email, which is not an identifier here and is not one there either.
     */
    private fun OidcClaims.toProfile(): SocialUserProfile =
        SocialUserProfile(
            providerUserId = subject,
            email = email,
            name = name ?: listOfNotNull(givenName, familyName).joinToString(" ").ifBlank { null },
            emailVerified = emailVerified,
            avatarUrl = picture,
            givenName = givenName,
            familyName = familyName,
        )

    private companion object {
        const val HTTP_OK = 200
    }
}

/** Why an OIDC exchange could not be completed. Carries no code, secret, verifier or token. */
class ProviderExchangeException(
    message: String,
) : Exception(message)
