package com.kauth.domain.model

import java.time.Instant

/**
 * How a provider is talked to: a compiled-in OAuth2 adapter, or generic OIDC discovery.
 */
enum class ProviderKind(
    val value: String,
) {
    OAUTH2("oauth2"),
    OIDC("oidc"),
    ;

    companion object {
        fun of(value: String): ProviderKind? = entries.find { it.value == value }
    }
}

/**
 * Domain model for a per-tenant identity provider configuration.
 *
 * Each tenant may configure one entry per provider key (Google, GitHub, or any OIDC issuer).
 * The client_secret is stored encrypted at rest and decrypted into this field
 * at runtime — never persist the plain value, and never expose it on a read.
 */
data class IdentityProvider(
    val id: Int? = null,
    val tenantId: TenantId,
    val provider: ProviderKey,
    val clientId: String,
    val clientSecret: String, // decrypted at runtime — AES-256-GCM in DB
    val enabled: Boolean = true,
    val kind: ProviderKind = ProviderKind.OAUTH2,
    val displayName: String? = null,
    val issuer: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val jwksUri: String? = null,
    val scopes: String = DEFAULT_OIDC_SCOPES,
    val jitEnabled: Boolean = false,
    val jitAllowedDomains: List<String> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    // The generated toString() of a data class prints every field, this one's plaintext secret
    // included, and one interpolation of a whole row into a log line would be enough.
    override fun toString(): String = "IdentityProvider(id=$id, tenantId=$tenantId, provider=$provider, kind=$kind)"
}

const val DEFAULT_OIDC_SCOPES = "openid email profile"
