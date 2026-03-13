package com.kauth.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OIDC-compliant token response returned to clients after successful authentication.
 *
 * Field names use snake_case per the OAuth2 / OIDC spec (RFC 6749 §5.1).
 * [id_token] is only present when "openid" is in the requested scopes.
 * [refresh_token] is absent for client_credentials flows (M2M).
 *
 * @see https://datatracker.ietf.org/doc/html/rfc6749#section-5.1
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token")       val access_token: String,
    @SerialName("token_type")         val token_type: String = "Bearer",
    @SerialName("expires_in")         val expires_in: Long,
    @SerialName("refresh_token")      val refresh_token: String? = null,
    @SerialName("refresh_expires_in") val refresh_expires_in: Long? = null,
    @SerialName("id_token")           val id_token: String? = null,
    @SerialName("scope")              val scope: String = "openid"
)
