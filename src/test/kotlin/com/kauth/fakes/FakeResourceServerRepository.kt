package com.kauth.fakes

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.ResourceServerId
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ResourceAuthorizationError
import com.kauth.domain.port.ResourceServerRepository
import java.time.Instant

class FakeResourceServerRepository(
    private val clientTenantLookup: (ApplicationId) -> TenantId? = { null },
) : ResourceServerRepository {
    private val byId = mutableMapOf<Int, ResourceServer>()
    private val authorizations = mutableMapOf<Int, MutableSet<Int>>()
    private var nextId = 1

    fun clear() {
        byId.clear()
        authorizations.clear()
        nextId = 1
    }

    fun seed(rs: ResourceServer): ResourceServer {
        val pk = rs.id?.value ?: nextId++
        val stored = rs.copy(id = ResourceServerId(pk))
        byId[pk] = stored
        if (pk >= nextId) nextId = pk + 1
        return stored
    }

    override fun findByTenantId(tenantId: TenantId): List<ResourceServer> =
        byId.values.filter { it.tenantId == tenantId }.sortedBy { it.id!!.value }

    override fun findById(
        tenantId: TenantId,
        id: ResourceServerId,
    ): ResourceServer? = byId[id.value]?.takeIf { it.tenantId == tenantId }

    override fun findByIdentifier(
        tenantId: TenantId,
        identifier: String,
    ): ResourceServer? = byId.values.firstOrNull { it.tenantId == tenantId && it.identifier == identifier }

    override fun create(
        tenantId: TenantId,
        identifier: String,
        name: String,
        description: String?,
    ): ResourceServer {
        val pk = nextId++
        val rs =
            ResourceServer(
                id = ResourceServerId(pk),
                tenantId = tenantId,
                identifier = identifier,
                name = name,
                description = description,
                enabled = true,
                createdAt = Instant.now(),
            )
        byId[pk] = rs
        return rs
    }

    override fun update(
        tenantId: TenantId,
        id: ResourceServerId,
        name: String,
        description: String?,
    ): ResourceServer {
        val current = byId[id.value]?.takeIf { it.tenantId == tenantId } ?: error("not found")
        val updated = current.copy(name = name, description = description)
        byId[id.value] = updated
        return updated
    }

    override fun setEnabled(
        tenantId: TenantId,
        id: ResourceServerId,
        enabled: Boolean,
    ) {
        val current = byId[id.value]?.takeIf { it.tenantId == tenantId } ?: return
        byId[id.value] = current.copy(enabled = enabled)
    }

    override fun delete(
        tenantId: TenantId,
        id: ResourceServerId,
    ) {
        val current = byId[id.value]?.takeIf { it.tenantId == tenantId } ?: return
        byId.remove(id.value)
        authorizations.values.forEach { it.remove(current.id!!.value) }
    }

    override fun listAuthorizedFor(clientPk: ApplicationId): List<ResourceServer> {
        val ids = authorizations[clientPk.value].orEmpty()
        return ids.mapNotNull { byId[it] }.sortedBy { it.id!!.value }
    }

    override fun setAuthorizedResources(
        clientPk: ApplicationId,
        resourceServerIds: List<ResourceServerId>,
    ): ResourceAuthorizationError? {
        val clientTenantId =
            clientTenantLookup(clientPk) ?: return ResourceAuthorizationError.UnknownClient

        for (rsId in resourceServerIds) {
            val rs = byId[rsId.value] ?: return ResourceAuthorizationError.UnknownResource(rsId)
            if (rs.tenantId != clientTenantId) return ResourceAuthorizationError.CrossTenant
        }

        authorizations[clientPk.value] = resourceServerIds.map { it.value }.toMutableSet()
        return null
    }
}
