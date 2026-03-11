package com.kauth.domain.model

import kotlinx.serialization.Serializable

/**
 * Token response returned to clients after successful authentication.
 * Serializable so Ktor's content negotiation can serialize it to JSON.
 */
@Serializable
data class TokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long
)
