package com.kauth.adapter.persistence

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.ResourceServerId
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ResourceAuthorizationError
import com.kauth.domain.port.ResourceServerRepository
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

class PostgresResourceServerRepository : ResourceServerRepository {
    override fun findByTenantId(tenantId: TenantId): List<ResourceServer> =
        transaction {
            ResourceServersTable
                .selectAll()
                .where { ResourceServersTable.tenantId eq tenantId.value }
                .orderBy(ResourceServersTable.id)
                .map { it.toResourceServer() }
        }

    override fun findById(
        tenantId: TenantId,
        id: ResourceServerId,
    ): ResourceServer? =
        transaction {
            ResourceServersTable
                .selectAll()
                .where { (ResourceServersTable.id eq id.value) and (ResourceServersTable.tenantId eq tenantId.value) }
                .singleOrNull()
                ?.toResourceServer()
        }

    override fun findByIdentifier(
        tenantId: TenantId,
        identifier: String,
    ): ResourceServer? =
        transaction {
            ResourceServersTable
                .selectAll()
                .where {
                    (ResourceServersTable.tenantId eq tenantId.value) and
                        (ResourceServersTable.identifier eq identifier)
                }.singleOrNull()
                ?.toResourceServer()
        }

    override fun findByIdentifiers(
        tenantId: TenantId,
        identifiers: Set<String>,
    ): List<ResourceServer> {
        if (identifiers.isEmpty()) return emptyList()
        return transaction {
            ResourceServersTable
                .selectAll()
                .where {
                    (ResourceServersTable.tenantId eq tenantId.value) and
                        (ResourceServersTable.identifier inList identifiers)
                }.orderBy(ResourceServersTable.id)
                .map { it.toResourceServer() }
        }
    }

    override fun create(
        tenantId: TenantId,
        identifier: String,
        name: String,
        description: String?,
        scopes: List<String>,
    ): ResourceServer =
        transaction {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val pk =
                ResourceServersTable.insert {
                    it[ResourceServersTable.tenantId] = tenantId.value
                    it[ResourceServersTable.identifier] = identifier
                    it[ResourceServersTable.name] = name
                    it[ResourceServersTable.description] = description
                    it[ResourceServersTable.scopes] = Json.encodeToString(scopes)
                    it[ResourceServersTable.enabled] = true
                    it[ResourceServersTable.createdAt] = now
                } get ResourceServersTable.id

            ResourceServer(
                id = ResourceServerId(pk),
                tenantId = tenantId,
                identifier = identifier,
                name = name,
                description = description,
                scopes = scopes,
                enabled = true,
                createdAt = now.toInstant(),
            )
        }

    override fun update(
        tenantId: TenantId,
        id: ResourceServerId,
        name: String,
        description: String?,
        scopes: List<String>,
    ): ResourceServer =
        transaction {
            ResourceServersTable.update(
                { (ResourceServersTable.id eq id.value) and (ResourceServersTable.tenantId eq tenantId.value) },
            ) {
                it[ResourceServersTable.name] = name
                it[ResourceServersTable.description] = description
                it[ResourceServersTable.scopes] = Json.encodeToString(scopes)
            }
            findById(tenantId, id) ?: error("ResourceServer $id not found after update")
        }

    override fun setEnabled(
        tenantId: TenantId,
        id: ResourceServerId,
        enabled: Boolean,
    ) {
        transaction {
            ResourceServersTable.update(
                { (ResourceServersTable.id eq id.value) and (ResourceServersTable.tenantId eq tenantId.value) },
            ) {
                it[ResourceServersTable.enabled] = enabled
            }
        }
    }

    override fun delete(
        tenantId: TenantId,
        id: ResourceServerId,
    ) {
        transaction {
            ResourceServersTable.deleteWhere {
                (ResourceServersTable.id eq id.value) and (ResourceServersTable.tenantId eq tenantId.value)
            }
        }
    }

    override fun listAuthorizedFor(clientPk: ApplicationId): List<ResourceServer> =
        transaction {
            (ResourceServersTable innerJoin ClientAuthorizedResourcesTable)
                .selectAll()
                .where { ClientAuthorizedResourcesTable.clientId eq clientPk.value }
                .orderBy(ResourceServersTable.id)
                .map { it.toResourceServer() }
        }

    override fun setAuthorizedResources(
        clientPk: ApplicationId,
        resourceServerIds: List<ResourceServerId>,
    ): ResourceAuthorizationError? =
        transaction {
            val clientRow =
                ClientsTable
                    .selectAll()
                    .where { ClientsTable.id eq clientPk.value }
                    .singleOrNull()
                    ?: return@transaction ResourceAuthorizationError.UnknownClient
            val clientTenantId = clientRow[ClientsTable.tenantId]

            for (rsId in resourceServerIds) {
                val rsRow =
                    ResourceServersTable
                        .selectAll()
                        .where { ResourceServersTable.id eq rsId.value }
                        .singleOrNull()
                        ?: return@transaction ResourceAuthorizationError.UnknownResource(rsId)
                if (rsRow[ResourceServersTable.tenantId] != clientTenantId) {
                    return@transaction ResourceAuthorizationError.CrossTenant
                }
            }

            val previousScopes =
                ClientAuthorizedScopesTable
                    .selectAll()
                    .where { ClientAuthorizedScopesTable.clientId eq clientPk.value }
                    .groupBy { it[ClientAuthorizedScopesTable.resourceServerId] }
                    .mapValues { (_, rows) -> rows.map { it[ClientAuthorizedScopesTable.scope] } }

            val previouslyAuthorized =
                ClientAuthorizedResourcesTable
                    .selectAll()
                    .where { ClientAuthorizedResourcesTable.clientId eq clientPk.value }
                    .map { it[ClientAuthorizedResourcesTable.resourceServerId] }
                    .toSet()

            // Deleting the authorization rows cascades their scope rows away (V67).
            ClientAuthorizedResourcesTable.deleteWhere {
                ClientAuthorizedResourcesTable.clientId eq clientPk.value
            }
            if (resourceServerIds.isNotEmpty()) {
                ClientAuthorizedResourcesTable.batchInsert(resourceServerIds) { rsId ->
                    this[ClientAuthorizedResourcesTable.clientId] = clientPk.value
                    this[ClientAuthorizedResourcesTable.resourceServerId] = rsId.value
                }
            }

            // A newly authorized resource starts with every scope it declares — the same default
            // the V67 backfill applied to existing pairs, and what the admin form shows checked.
            // An operator narrows from there; we never silently grant less than they last chose,
            // and re-saving an unchanged authorization must not reset their choices.
            for (rsId in resourceServerIds) {
                val declared =
                    ResourceServersTable
                        .selectAll()
                        .where { ResourceServersTable.id eq rsId.value }
                        .singleOrNull()
                        ?.let { Json.decodeFromString<List<String>>(it[ResourceServersTable.scopes].ifBlank { "[]" }) }
                        ?: emptyList()
                val seed =
                    if (rsId.value in previouslyAuthorized) {
                        // Preserve what the operator had chosen before this save.
                        previousScopes[rsId.value] ?: declared
                    } else {
                        declared
                    }
                for (sc in seed) {
                    ClientAuthorizedScopesTable.insert {
                        it[clientId] = clientPk.value
                        it[resourceServerId] = rsId.value
                        it[scope] = sc
                    }
                }
            }
            null
        }

    private fun ResultRow.toResourceServer() =
        ResourceServer(
            id = ResourceServerId(this[ResourceServersTable.id]),
            tenantId = TenantId(this[ResourceServersTable.tenantId]),
            identifier = this[ResourceServersTable.identifier],
            name = this[ResourceServersTable.name],
            description = this[ResourceServersTable.description],
            enabled = this[ResourceServersTable.enabled],
            scopes = Json.decodeFromString(this[ResourceServersTable.scopes].ifBlank { "[]" }),
            createdAt = this[ResourceServersTable.createdAt].toInstant(),
        )

    override fun findAllowedScopes(clientPk: ApplicationId): Map<ResourceServerId, Set<String>> =
        transaction {
            ClientAuthorizedScopesTable
                .selectAll()
                .where { ClientAuthorizedScopesTable.clientId eq clientPk.value }
                .groupBy { ResourceServerId(it[ClientAuthorizedScopesTable.resourceServerId]) }
                .mapValues { (_, rows) -> rows.map { it[ClientAuthorizedScopesTable.scope] }.toSet() }
        }

    override fun setAllowedScopes(
        clientPk: ApplicationId,
        resourceServerId: ResourceServerId,
        scopes: Set<String>,
    ): ResourceAuthorizationError? =
        transaction {
            val authorized =
                ClientAuthorizedResourcesTable
                    .selectAll()
                    .where {
                        (ClientAuthorizedResourcesTable.clientId eq clientPk.value) and
                            (ClientAuthorizedResourcesTable.resourceServerId eq resourceServerId.value)
                    }.any()
            if (!authorized) return@transaction ResourceAuthorizationError.NotAuthorizedForResource(resourceServerId)

            val declared =
                ResourceServersTable
                    .selectAll()
                    .where { ResourceServersTable.id eq resourceServerId.value }
                    .singleOrNull()
                    ?.let {
                        Json.decodeFromString<List<String>>(
                            it[ResourceServersTable.scopes].ifBlank { "[]" },
                        )
                    }?.toSet()
                    ?: return@transaction ResourceAuthorizationError.UnknownResource(resourceServerId)

            // Refuse rather than silently store a scope the resource server does not offer —
            // otherwise the allowlist drifts from the API it is meant to constrain.
            val undeclared = scopes - declared
            if (undeclared.isNotEmpty()) {
                return@transaction ResourceAuthorizationError.UndeclaredScope(resourceServerId, undeclared)
            }

            ClientAuthorizedScopesTable.deleteWhere {
                (ClientAuthorizedScopesTable.clientId eq clientPk.value) and
                    (ClientAuthorizedScopesTable.resourceServerId eq resourceServerId.value)
            }
            for (sc in scopes) {
                ClientAuthorizedScopesTable.insert {
                    it[clientId] = clientPk.value
                    it[ClientAuthorizedScopesTable.resourceServerId] = resourceServerId.value
                    it[scope] = sc
                }
            }
            null
        }
}
