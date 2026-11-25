package com.kauth.domain.model

import java.time.Instant

/** What a user allowed one application to access. Scopes only grow until the user revokes it. */
data class UserConsent(
    val tenantId: TenantId,
    val userId: UserId,
    val applicationId: ApplicationId,
    val scopes: Set<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
