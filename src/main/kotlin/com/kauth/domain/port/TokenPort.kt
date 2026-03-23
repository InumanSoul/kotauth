package com.kauth.domain.port

import com.kauth.domain.model.AccessTokenClaims
import com.kauth.domain.model.Application
import com.kauth.domain.model.Role
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User

/**
 * Port (outbound) — defines what the domain needs from a token provider.
 *
 * Implementations handle the cryptographic details (RS256 signing, JWKS,
 * key loading). The domain services work exclusively with this abstraction.
 *
 * Implemented by [JwtTokenAdapter].
 */
interface TokenPort {
    /**
     * Issues a full token set (access + refresh + id_token if openid in scope)
     * for a user authentication event.
     *
     * [roles] are the user's effective roles (direct + group + composite expanded).
     * They are embedded in the token as:
     *   - `realm_access.roles` for tenant-scoped roles
     *   - `resource_access.{clientId}.roles` for client-scoped roles
     */
    fun issueUserTokens(
        user: User,
        tenant: Tenant,
        client: Application?,
        scopes: List<String>,
        nonce: String? = null,
        roles: List<Role> = emptyList(),
    ): TokenResponse

    /**
     * Issues a client credentials access token (no user context, no refresh token).
     */
    fun issueClientCredentialsToken(
        tenant: Tenant,
        client: Application,
        scopes: List<String>,
    ): String

    /**
     * Decodes and verifies an access token's signature and expiry.
     * Returns null if the token is invalid, expired, or signed by an unknown key.
     */
    fun decodeAccessToken(token: String): AccessTokenClaims?

    /**
     * Returns the JWKS (JSON Web Key Set) for a tenant's active signing keys.
     */
    fun getTenantJwks(tenantId: TenantId): List<Map<String, Any>>
}
