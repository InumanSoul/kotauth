package com.kauth.domain.model

import java.time.Instant

/**
 * Domain model for a per-tenant OAuth2 identity provider configuration.
 *
 * Each tenant may configure one entry per provider key (Google, GitHub, etc.).
 * The client_secret is stored encrypted at rest and decrypted into this field
 * at runtime — never persist the plain value.
 */
data class IdentityProvider(
    val id: Int? = null,
    val tenantId: TenantId,
    val provider: ProviderKey,
    val clientId: String,
    val clientSecret: String, // decrypted at runtime — AES-256-GCM in DB
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
