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
    val createdAt: Instant = Instant.now(),
)
