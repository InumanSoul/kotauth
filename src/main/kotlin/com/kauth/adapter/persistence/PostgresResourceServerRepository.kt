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

    override fun create(
        tenantId: TenantId,
        identifier: String,
        name: String,
        description: String?,
    ): ResourceServer =
        transaction {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val pk =
                ResourceServersTable.insert {
                    it[ResourceServersTable.tenantId] = tenantId.value
                    it[ResourceServersTable.identifier] = identifier
                    it[ResourceServersTable.name] = name
                    it[ResourceServersTable.description] = description
                    it[ResourceServersTable.enabled] = true
                    it[ResourceServersTable.createdAt] = now
                } get ResourceServersTable.id

            ResourceServer(
                id = ResourceServerId(pk),
                tenantId = tenantId,
                identifier = identifier,
                name = name,
                description = description,
                enabled = true,
                createdAt = now.toInstant(),
            )
        }

    override fun update(
        tenantId: TenantId,
        id: ResourceServerId,
        name: String,
        description: String?,
    ): ResourceServer =
        transaction {
            ResourceServersTable.update(
                { (ResourceServersTable.id eq id.value) and (ResourceServersTable.tenantId eq tenantId.value) },
            ) {
                it[ResourceServersTable.name] = name
                it[ResourceServersTable.description] = description
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

            ClientAuthorizedResourcesTable.deleteWhere {
                ClientAuthorizedResourcesTable.clientId eq clientPk.value
            }
            if (resourceServerIds.isNotEmpty()) {
                ClientAuthorizedResourcesTable.batchInsert(resourceServerIds) { rsId ->
                    this[ClientAuthorizedResourcesTable.clientId] = clientPk.value
                    this[ClientAuthorizedResourcesTable.resourceServerId] = rsId.value
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
}
