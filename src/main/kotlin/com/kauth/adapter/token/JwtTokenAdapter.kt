package com.kauth.adapter.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.port.TokenPort
import java.util.*

/**
 * Token adapter — implements the TokenPort using Auth0's JWT library.
 *
 * Isolated here so the domain never imports JWT classes.
 * Swapping to PASETO or any other token format = replace this file only.
 */
class JwtTokenAdapter(
    private val issuer: String,
    private val audience: String,
    private val algorithm: Algorithm
) : TokenPort {

    override fun createTokenSet(username: String): TokenResponse {
        val accessToken = JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("username", username)
            .withExpiresAt(Date(System.currentTimeMillis() + TOKEN_EXPIRY_MS))
            .sign(algorithm)

        return TokenResponse(
            access_token = accessToken,
            refresh_token = UUID.randomUUID().toString(),
            expires_in = TOKEN_EXPIRY_SECONDS
        )
    }

    companion object {
        private const val TOKEN_EXPIRY_MS = 3_600_000L   // 1 hour
        private const val TOKEN_EXPIRY_SECONDS = 3600L
    }
}
