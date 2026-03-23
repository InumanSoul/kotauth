package com.kauth.domain.model

import java.time.Instant

/**
 * A short-lived, single-use code issued by the authorization endpoint.
 *
 * Lifecycle:
 *   1. Issued after successful user authentication in the authorization flow.
 *   2. Client exchanges it at the token endpoint (with code_verifier for PKCE).
 *   3. Marked [usedAt] on first use — subsequent attempts with the same code
 *      must be rejected (replay attack prevention).
 *   4. Expires after [expiresAt] regardless of use.
 *
 * PKCE fields ([codeChallenge], [codeChallengeMethod]):
 *   - Required when the issuing client has access_type = PUBLIC.
 *   - [codeChallengeMethod] is always "S256" — "plain" is not accepted.
 *   - At exchange, SHA-256(code_verifier) must equal [codeChallenge] (base64url).
 */
data class AuthorizationCode(
    val id: Int? = null,
    val code: String,
    val tenantId: TenantId,
    val clientId: ApplicationId,
    val userId: UserId,
    val redirectUri: String,
    val scopes: String = "openid",
    val codeChallenge: String? = null,
    val codeChallengeMethod: String? = null, // "S256" or null
    val nonce: String? = null,
    val state: String? = null,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
) {
    val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)
    val isUsed: Boolean get() = usedAt != null
    val isValid: Boolean get() = !isExpired && !isUsed
}
