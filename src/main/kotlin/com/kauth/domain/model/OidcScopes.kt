package com.kauth.domain.model

/** The OpenID Connect scopes Kotauth serves. They describe the user, not an API, so no resource narrows them. */
object OidcScopes {
    val IDENTITY: Set<String> = linkedSetOf("openid", "profile", "email")
}
