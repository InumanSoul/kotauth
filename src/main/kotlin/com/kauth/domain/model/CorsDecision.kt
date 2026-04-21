package com.kauth.domain.model

sealed class CorsDecision {
    data object Public : CorsDecision()

    data class Allowed(
        val origin: String,
        val allowCredentials: Boolean,
    ) : CorsDecision()

    data object Denied : CorsDecision()
}
