package com.kauth.domain.port

import com.kauth.domain.model.AccessTokenClaims
import com.kauth.domain.model.Application
import com.kauth.domain.model.Role
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import java.time.Instant

/**
 * Port (outbound) — defines what the domain needs from a token provider.
 *
 * Implementations handle the cryptographic details (RS256 signing, JWKS,
 * key loading). The domain services work exclusively with this abstraction.
 *
 * Implemented by JwtTokenAdapter (in the adapter/token/ package).
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
     *
     * [customAccessClaims] and [customIdClaims] are already-projected maps of
     * claim-name → value from [com.kauth.domain.service.ClaimMapperService].
     * The adapter stamps each entry onto the corresponding JWT builder without
     * further logic. Default empty maps preserve pre-feature token shape.
     */
    fun issueUserTokens(
        user: User,
        tenant: Tenant,
        client: Application?,
        scopes: List<String>,
        nonce: String? = null,
        roles: List<Role> = emptyList(),
        customAccessClaims: Map<String, String> = emptyMap(),
        customIdClaims: Map<String, String> = emptyMap(),
        /**
         * Moment of the user's most recent interactive credential proof.
         * Surfaces as the OIDC `auth_time` claim on the ID token (Core §2 / §3.1.3.7).
         * Null is allowed for token-issuance paths that do not have it (refresh
         * token, legacy callers); silent auth and fresh logins always populate it.
         */
        authTime: Instant? = null,
        /**
         * The acting party when this token is issued under impersonation.
         * When non-null, the adapter stamps a nested `act` claim on both the
         * access token and id_token per RFC 8693 §4.1, with `act.sub` set to
         * the acting user's id. `sub` remains the impersonated user.
         */
        actingSubject: UserId? = null,
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

    /**
     * Invalidates the cached signing key for a tenant.
     * Must be called after key rotation so new tokens use the new key.
     */
    fun invalidateSigningKeyCache(tenantId: TenantId)
}
