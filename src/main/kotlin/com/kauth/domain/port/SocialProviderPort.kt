package com.kauth.domain.port

import com.kauth.domain.model.ProviderKey

/**
 * The two per-request values an OIDC round-trip is bound by: the nonce that ties the ID token to
 * this request, and the PKCE verifier that ties the token exchange to whoever began it.
 *
 * The verifier travels rather than the challenge so only one place ever derives one from the
 * other — a challenge sent at the redirect and a verifier replayed at the exchange cannot drift.
 * Neither value may be logged.
 */
data class OidcRequestBinding(
    val nonce: String,
    val codeVerifier: String,
)

/**
 * Port — outbound HTTP calls to a social OAuth2 provider.
 *
 * Each concrete adapter (GoogleOAuthAdapter, GitHubOAuthAdapter) handles
 * the provider-specific token exchange and userinfo endpoints.
 * The domain service depends only on this interface — zero provider coupling.
 */
interface SocialProviderPort {
    /** Which provider this adapter handles. */
    val provider: ProviderKey

    /**
     * Exchanges an authorization [code] for a token set, then fetches the
     * user's profile from the provider's userinfo endpoint.
     *
     * @param code        The authorization code from the OAuth2 callback.
     * @param redirectUri The redirect URI used in the original authorization request
     *                    (must match exactly — required by OAuth2 spec).
     * @param clientId    Provider OAuth2 client ID.
     * @param clientSecret Provider OAuth2 client secret.
     * @param binding     The nonce and PKCE verifier this request began with. The compiled-in
     *                    OAuth2 adapters ignore it; an OIDC provider requires it.
     */
    fun exchangeCodeForProfile(
        code: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String,
        binding: OidcRequestBinding? = null,
    ): SocialUserProfile

    /**
     * Builds the authorization URL to redirect the user to.
     *
     * @param clientId    Provider OAuth2 client ID.
     * @param redirectUri Registered callback URI.
     * @param state       CSRF-protection state token (signed by EncryptionService).
     * @param scopes      OAuth2 scopes to request (defaults are set per provider).
     * @param binding     The nonce and PKCE verifier this request begins with. The compiled-in
     *                    OAuth2 adapters ignore it; an OIDC provider sends both.
     */
    fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        scopes: List<String> = emptyList(),
        binding: OidcRequestBinding? = null,
    ): String
}

/**
 * Token response from the provider's token endpoint.
 * Used internally by adapters — not exposed to the domain service.
 */
data class SocialTokenResponse(
    val accessToken: String,
    val tokenType: String,
    val idToken: String? = null, // Google only
    val scope: String? = null,
)

/**
 * Normalized user profile returned by the provider.
 * The domain service uses this to find or create the local user.
 */
data class SocialUserProfile(
    /** Stable, unique identifier for this user within the provider. */
    val providerUserId: String,
    /** Primary email address (may be null if not authorized by the user). */
    val email: String?,
    /** Full display name from the provider. */
    val name: String?,
    /** Whether the email has been verified by the provider. */
    val emailVerified: Boolean = false,
    /** Profile picture URL from the provider (optional). */
    val avatarUrl: String? = null,
    /** OIDC `given_name`. Null when the provider did not assert it. */
    val givenName: String? = null,
    /** OIDC `family_name`. Null when the provider did not assert it. */
    val familyName: String? = null,
)
