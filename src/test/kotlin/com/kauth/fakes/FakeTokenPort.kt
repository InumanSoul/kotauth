package com.kauth.fakes

import com.kauth.domain.model.AccessTokenClaims
import com.kauth.domain.model.Application
import com.kauth.domain.model.Role
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.TokenPort

/**
 * Deterministic TokenPort for unit tests.
 *
 * Returns structurally valid but non-cryptographic tokens.
 * The token values are predictable so tests can assert on them.
 * RS256 signing and JWKS are not needed for domain service tests.
 */
class FakeTokenPort : TokenPort {
    // Counter ensures each call produces unique access/refresh tokens — required
    // for rotation tests where replaying the old token must fail because the new
    // session has a different refresh token hash.
    private var callCount = 0

    var claimsToReturn: AccessTokenClaims? = null
    var jwksToReturn: List<Map<String, Any>> = emptyList()

    /** Captured on every issueUserTokens call — tests can assert on projected claims. */
    var lastCustomAccessClaims: Map<String, String> = emptyMap()
        private set
    var lastCustomIdClaims: Map<String, String> = emptyMap()
        private set
    var lastAuthTime: java.time.Instant? = null
        private set
    var lastActingSubject: UserId? = null
        private set

    fun reset() {
        callCount = 0
        claimsToReturn = null
        jwksToReturn = emptyList()
        lastCustomAccessClaims = emptyMap()
        lastCustomIdClaims = emptyMap()
        lastAuthTime = null
        lastActingSubject = null
    }

    override fun issueUserTokens(
        user: User,
        tenant: Tenant,
        client: Application?,
        scopes: List<String>,
        nonce: String?,
        roles: List<Role>,
        customAccessClaims: Map<String, String>,
        customIdClaims: Map<String, String>,
        authTime: java.time.Instant?,
        actingSubject: UserId?,
    ): TokenResponse {
        val n = ++callCount
        lastCustomAccessClaims = customAccessClaims
        lastCustomIdClaims = customIdClaims
        lastAuthTime = authTime
        lastActingSubject = actingSubject
        val subjectMarker = if (actingSubject != null) "imp.${actingSubject.value}." else ""
        return TokenResponse(
            access_token = "fake.access.$subjectMarker${user.username}.$n",
            token_type = "Bearer",
            expires_in = tenant.tokenExpirySeconds,
            refresh_token = "fake.refresh.$subjectMarker${user.username}.$n",
            refresh_expires_in = tenant.refreshTokenExpirySeconds,
            id_token = if ("openid" in scopes) "fake.id.$subjectMarker${user.username}.$n" else null,
            scope = scopes.joinToString(" "),
        )
    }

    var lastClientCredentialsAudiences: List<String> = emptyList()
        private set

    override fun issueClientCredentialsToken(
        tenant: Tenant,
        client: Application,
        scopes: List<String>,
        audiences: List<String>,
    ): String {
        lastClientCredentialsAudiences = audiences
        return "fake.m2m.${client.clientId}.${audiences.joinToString("|")}"
    }

    override fun decodeAccessToken(
        token: String,
        expectedIssuer: String,
    ): AccessTokenClaims? = claimsToReturn

    override fun issuerFor(tenant: Tenant): String = tenant.issuerUrl ?: "https://fake-issuer/${tenant.slug}"

    override fun getTenantJwks(tenantId: TenantId): List<Map<String, Any>> = jwksToReturn

    var cacheInvalidatedForTenant: TenantId? = null
        private set

    override fun invalidateSigningKeyCache(tenantId: TenantId) {
        cacheInvalidatedForTenant = tenantId
    }
}
