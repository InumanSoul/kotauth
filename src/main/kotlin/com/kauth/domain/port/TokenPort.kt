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

interface TokenPort {
    fun issueUserTokens(
        user: User,
        tenant: Tenant,
        client: Application?,
        scopes: List<String>,
        nonce: String? = null,
        roles: List<Role> = emptyList(),
        customAccessClaims: Map<String, String> = emptyMap(),
        customIdClaims: Map<String, String> = emptyMap(),
        authTime: Instant? = null,
        // Sets the RFC 8693 §4.1 nested `act` claim on access + id tokens; `sub` stays the impersonated user.
        actingSubject: UserId? = null,
    ): TokenResponse

    fun issueClientCredentialsToken(
        tenant: Tenant,
        client: Application,
        scopes: List<String>,
        audiences: List<String>,
    ): String

    /** Verifies the token signature, expiry, and `iss` claim against [expectedIssuer]. */
    fun decodeAccessToken(
        token: String,
        expectedIssuer: String,
    ): AccessTokenClaims?

    /** Issuer URL for [tenant] — matches the `iss` claim on tokens minted for it. */
    fun issuerFor(tenant: Tenant): String

    fun getTenantJwks(tenantId: TenantId): List<Map<String, Any>>

    /** Must be called after key rotation so new tokens use the new key. */
    fun invalidateSigningKeyCache(tenantId: TenantId)
}
