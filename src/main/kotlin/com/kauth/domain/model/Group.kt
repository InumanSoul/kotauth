package com.kauth.domain.model

import java.time.Instant

/**
 * A hierarchical group within a tenant.
 *
 * Groups provide a way to organise users and assign roles in bulk.
 * When a user belongs to a group, they inherit all roles assigned to that group
 * and all ancestor groups up the hierarchy.
 *
 * [parentGroupId] enables nesting. A null value means this is a top-level group.
 * [attributes] stores arbitrary key-value metadata (JSONB in Postgres).
 */
data class Group(
    val id: Int? = null,
    val tenantId: Int,
    val name: String,
    val description: String? = null,
    val parentGroupId: Int? = null,
    val attributes: Map<String, String> = emptyMap(),
    val roleIds: List<Int> = emptyList(),
    val createdAt: Instant = Instant.now(),
)
