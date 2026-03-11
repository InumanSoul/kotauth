package com.kauth.domain.port

import com.kauth.domain.model.TokenResponse

/**
 * Port (outbound) — defines WHAT the domain needs from a token provider.
 * The domain doesn't know (or care) that this is JWT, PASETO, or anything else.
 */
interface TokenPort {
    fun createTokenSet(username: String): TokenResponse
}
