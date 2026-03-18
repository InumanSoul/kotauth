package com.kauth.domain.model

import java.time.Instant

/**
 * Domain model linking a social provider identity to a local KotAuth user.
 *
 * One user may have multiple social accounts (one per provider).
 * A provider identity (provider + provider_user_id) is unique per tenant.
 */
data class SocialAccount(
    val id: Int? = null,
    val userId: Int,
    val tenantId: Int,
    val provider: SocialProvider,
    /** The stable unique identifier from the provider (Google sub, GitHub id). */
    val providerUserId: String,
    /** Email from the provider at time of linking — may differ from local user email. */
    val providerEmail: String?,
    /** Display name from the provider. */
    val providerName: String?,
    /** Avatar URL from the provider (optional). */
    val avatarUrl: String? = null,
    val linkedAt: Instant = Instant.now(),
)
