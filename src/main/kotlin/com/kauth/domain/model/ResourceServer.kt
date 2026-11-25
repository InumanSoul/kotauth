package com.kauth.domain.model

import java.time.Instant

@JvmInline
value class ResourceServerId(
    val value: Int,
)

data class ResourceServer(
    val id: ResourceServerId? = null,
    val tenantId: TenantId,
    val identifier: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val scopes: List<String> = emptyList(),
    /** Text the consent screen shows for a declared scope, keyed by the scope. */
    val scopeDescriptions: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
)
