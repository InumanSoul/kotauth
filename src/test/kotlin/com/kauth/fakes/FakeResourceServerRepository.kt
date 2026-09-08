package com.kauth.fakes

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.ResourceServerId
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ResourceAuthorizationError
import com.kauth.domain.port.ResourceServerRepository
import java.time.Instant

class FakeResourceServerRepository : ResourceServerRepository {
    private val byId = mutableMapOf<Int, ResourceServer>()
    private val authorizations = mutableMapOf<Int, MutableSet<Int>>()
    private val clientTenants = mutableMapOf<Int, TenantId>()
    private val allowedScopes = mutableMapOf<Pair<Int, Int>, MutableSet<String>>()
    private var nextId = 1

    fun clear() {
        byId.clear()
        authorizations.clear()
        clientTenants.clear()
        nextId = 1
    }

    fun registerClient(
        clientPk: ApplicationId,
        tenantId: TenantId,
    ) {
        clientTenants[clientPk.value] = tenantId
    }

    fun seed(rs: ResourceServer): ResourceServer {
        val pk = rs.id?.value ?: nextId++
        val stored = rs.copy(id = ResourceServerId(pk))
        byId[pk] = stored
        if (pk >= nextId) nextId = pk + 1
        return stored
    }

    /**
     * Seed a resource server and authorize [clientPk] for it, granting every scope it declares.
     *
     * A real flow reaches `narrowScopes` only after `resolveAudiences` has confirmed the client is
     * authorized for the resource, so a fixture that seeds without authorizing does not represent
     * a reachable state. Use this rather than [seed] wherever the test exercises token issuance.
     */
    fun seedAuthorized(
        rs: ResourceServer,
        clientPk: ApplicationId,
        tenantId: TenantId,
    ): ResourceServer {
        val stored = seed(rs)
        registerClient(clientPk, tenantId)
        val existing = authorizations[clientPk.value].orEmpty().map { ResourceServerId(it) }
        setAuthorizedResources(clientPk, existing + stored.id!!)
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

    override fun findByIdentifiers(
        tenantId: TenantId,
        identifiers: Set<String>,
    ): List<ResourceServer> =
        byId.values
            .filter { it.tenantId == tenantId && it.identifier in identifiers }
            .sortedBy { it.id!!.value }

    override fun create(
        tenantId: TenantId,
        identifier: String,
        name: String,
        description: String?,
        scopes: List<String>,
    ): ResourceServer {
        val pk = nextId++
        val rs =
            ResourceServer(
                id = ResourceServerId(pk),
                tenantId = tenantId,
                identifier = identifier,
                name = name,
                description = description,
                scopes = scopes,
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
        scopes: List<String>,
    ): ResourceServer {
        val current = byId[id.value]?.takeIf { it.tenantId == tenantId } ?: error("not found")
        val updated = current.copy(name = name, description = description, scopes = scopes)
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
        val clientTenantId = clientTenants[clientPk.value] ?: return ResourceAuthorizationError.UnknownClient

        for (rsId in resourceServerIds) {
            val rs = byId[rsId.value] ?: return ResourceAuthorizationError.UnknownResource(rsId)
            if (rs.tenantId != clientTenantId) return ResourceAuthorizationError.CrossTenant
        }

        val previous = authorizations[clientPk.value].orEmpty().toSet()
        authorizations[clientPk.value] = resourceServerIds.map { it.value }.toMutableSet()

        // Mirror production: a newly authorized resource starts with every scope it declares;
        // a resource that was already authorized keeps whatever the operator had chosen.
        allowedScopes.keys.filter { it.first == clientPk.value }.forEach { key ->
            if (key.second !in resourceServerIds.map { it.value }.toSet()) allowedScopes.remove(key)
        }
        for (rsId in resourceServerIds) {
            if (rsId.value in previous && allowedScopes.containsKey(clientPk.value to rsId.value)) continue
            allowedScopes[clientPk.value to rsId.value] =
                (byId[rsId.value]?.scopes ?: emptyList()).toMutableSet()
        }
        return null
    }

    override fun findAllowedScopes(clientPk: ApplicationId): Map<ResourceServerId, Set<String>> =
        allowedScopes
            .filterKeys { it.first == clientPk.value }
            .map { (k, v) -> ResourceServerId(k.second) to v.toSet() }
            .toMap()

    override fun setAllowedScopes(
        clientPk: ApplicationId,
        resourceServerId: ResourceServerId,
        scopes: Set<String>,
    ): ResourceAuthorizationError? {
        if (authorizations[clientPk.value]?.contains(resourceServerId.value) != true) {
            return ResourceAuthorizationError.NotAuthorizedForResource(resourceServerId)
        }
        val declared = byId[resourceServerId.value]?.scopes?.toSet() ?: emptySet()
        val undeclared = scopes - declared
        if (undeclared.isNotEmpty()) {
            return ResourceAuthorizationError.UndeclaredScope(resourceServerId, undeclared)
        }
        allowedScopes[clientPk.value to resourceServerId.value] = scopes.toMutableSet()
        return null
    }

    /** Test helper: grant every scope a resource declares, mirroring the V67 backfill. */
    fun grantAllDeclaredScopes(
        clientPk: ApplicationId,
        resourceServerId: ResourceServerId,
    ) {
        allowedScopes[clientPk.value to resourceServerId.value] =
            (byId[resourceServerId.value]?.scopes ?: emptyList()).toMutableSet()
    }
}
