package com.kauth.domain.model

sealed class Requirement {
    data object SmtpRequired : Requirement()

    // Reserved for future use (e.g. an "Available providers" info panel).
    // No active emit sites — SecurityMethodsService skips unconfigured providers entirely.
    data class OAuthCredentialsRequired(
        val provider: String,
    ) : Requirement()

    fun isBlocking(
        tenant: Tenant,
        identityProviderConfigured: (provider: String) -> Boolean,
    ): Boolean =
        when (this) {
            SmtpRequired -> !tenant.isSmtpReady
            is OAuthCredentialsRequired -> !identityProviderConfigured(provider)
        }
}
