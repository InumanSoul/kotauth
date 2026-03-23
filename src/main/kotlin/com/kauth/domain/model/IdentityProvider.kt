package com.kauth.domain.model

import java.time.Instant

/**
 * Domain model for a per-tenant OAuth2 identity provider configuration.
 *
 * Each tenant may configure one entry per provider (Google, GitHub, etc.).
 * The client_secret is stored encrypted at rest and decrypted into this field
 * at runtime — never persist the plain value.
 */
data class IdentityProvider(
    val id: Int? = null,
    val tenantId: TenantId,
    val provider: SocialProvider,
    val clientId: String,
    val clientSecret: String, // decrypted at runtime — AES-256-GCM in DB
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

/**
 * Supported social identity providers.
 * The [value] string must match the 'provider' column values in identity_providers / social_accounts.
 */
enum class SocialProvider(
    val value: String,
    val displayName: String,
) {
    GOOGLE("google", "Google"),
    GITHUB("github", "GitHub"),
    ;

    companion object {
        fun fromValue(value: String): SocialProvider =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown social provider: $value")

        fun fromValueOrNull(value: String): SocialProvider? = entries.firstOrNull { it.value == value }
    }
}
