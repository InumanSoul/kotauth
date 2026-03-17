package com.kauth.fakes

import com.kauth.domain.model.AccessTokenClaims
import com.kauth.domain.model.Application
import com.kauth.domain.model.Role
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
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

    fun reset() { callCount = 0 }

    override fun issueUserTokens(
        user   : User,
        tenant : Tenant,
        client : Application?,
        scopes : List<String>,
        nonce  : String?,
        roles  : List<Role>
    ): TokenResponse {
        val n = ++callCount
        return TokenResponse(
            access_token       = "fake.access.${user.username}.$n",
            token_type         = "Bearer",
            expires_in         = tenant.tokenExpirySeconds,
            refresh_token      = "fake.refresh.${user.username}.$n",
            refresh_expires_in = tenant.refreshTokenExpirySeconds,
            id_token           = if ("openid" in scopes) "fake.id.${user.username}.$n" else null,
            scope              = scopes.joinToString(" ")
        )
    }

    override fun issueClientCredentialsToken(
        tenant : Tenant,
        client : Application,
        scopes : List<String>
    ): String = "fake.m2m.${client.clientId}"

    /** Not needed for domain service tests — return null. */
    override fun decodeAccessToken(token: String): AccessTokenClaims? = null

    override fun getTenantJwks(tenantId: Int): List<Map<String, Any>> = emptyList()
}
