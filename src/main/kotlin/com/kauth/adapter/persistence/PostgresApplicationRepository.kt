package com.kauth.adapter.persistence

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ApplicationRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Persistence adapter — implements ApplicationRepository using PostgreSQL + Exposed.
 *
 * Reads from ClientsTable + ClientRedirectUrisTable (V2 migration schema).
 * Follows the same pattern as PostgresTenantRepository.
 */
class PostgresApplicationRepository : ApplicationRepository {
    override fun findByTenantId(tenantId: TenantId): List<Application> =
        transaction {
            ClientsTable
                .selectAll()
                .where { ClientsTable.tenantId eq tenantId.value }
                .orderBy(ClientsTable.id)
                .map { row ->
                    val uris = urisForClientPk(row[ClientsTable.id])
                    row.toApplication(uris)
                }
        }

    override fun findByClientId(
        tenantId: TenantId,
        clientId: String,
    ): Application? =
        transaction {
            val row =
                ClientsTable
                    .selectAll()
                    .where { (ClientsTable.tenantId eq tenantId.value) and (ClientsTable.clientId eq clientId) }
                    .singleOrNull() ?: return@transaction null
            val uris = urisForClientPk(row[ClientsTable.id])
            row.toApplication(uris)
        }

    override fun findById(id: ApplicationId): Application? =
        transaction {
            val row =
                ClientsTable
                    .selectAll()
                    .where { ClientsTable.id eq id.value }
                    .singleOrNull() ?: return@transaction null
            val uris = urisForClientPk(row[ClientsTable.id])
            row.toApplication(uris)
        }

    override fun findClientSecretHash(clientPk: ApplicationId): String? =
        transaction {
            ClientsTable
                .selectAll()
                .where { ClientsTable.id eq clientPk.value }
                .map { it[ClientsTable.clientSecretHash] }
                .singleOrNull()
        }

    override fun setClientSecretHash(
        clientPk: ApplicationId,
        secretHash: String,
    ) = transaction {
        ClientsTable.update({ ClientsTable.id eq clientPk.value }) {
            it[clientSecretHash] = secretHash
        }
        Unit
    }

    override fun existsByClientId(
        tenantId: TenantId,
        clientId: String,
    ): Boolean =
        transaction {
            ClientsTable
                .selectAll()
                .where { (ClientsTable.tenantId eq tenantId.value) and (ClientsTable.clientId eq clientId) }
                .count() > 0
        }

    override fun create(
        tenantId: TenantId,
        clientId: String,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>,
    ): Application =
        transaction {
            val insertedPk =
                ClientsTable.insert {
                    it[ClientsTable.tenantId] = tenantId.value
                    it[ClientsTable.clientId] = clientId
                    it[ClientsTable.name] = name
                    it[ClientsTable.description] = description
                    it[ClientsTable.accessType] = AccessType.fromValue(accessType)
                } get ClientsTable.id

            if (redirectUris.isNotEmpty()) {
                ClientRedirectUrisTable.batchInsert(redirectUris) { uri ->
                    this[ClientRedirectUrisTable.clientId] = insertedPk
                    this[ClientRedirectUrisTable.uri] = uri
                }
            }

            val row = ClientsTable.selectAll().where { ClientsTable.id eq insertedPk }.single()
            val uris = urisForClientPk(insertedPk)
            row.toApplication(uris)
        }

    override fun update(
        appId: ApplicationId,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>,
    ): Application =
        transaction {
            ClientsTable.update({ ClientsTable.id eq appId.value }) {
                it[ClientsTable.name] = name
                it[ClientsTable.description] = description
                it[ClientsTable.accessType] = AccessType.fromValue(accessType)
            }
            // Replace all redirect URIs atomically
            ClientRedirectUrisTable.deleteWhere { ClientRedirectUrisTable.clientId eq appId.value }
            if (redirectUris.isNotEmpty()) {
                ClientRedirectUrisTable.batchInsert(redirectUris) { uri ->
                    this[ClientRedirectUrisTable.clientId] = appId.value
                    this[ClientRedirectUrisTable.uri] = uri
                }
            }
            val row = ClientsTable.selectAll().where { ClientsTable.id eq appId.value }.single()
            row.toApplication(urisForClientPk(appId.value))
        }

    override fun setEnabled(
        appId: ApplicationId,
        enabled: Boolean,
    ) = transaction {
        ClientsTable.update({ ClientsTable.id eq appId.value }) {
            it[ClientsTable.enabled] = enabled
        }
        Unit
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun urisForClientPk(clientPk: Int): List<String> =
        ClientRedirectUrisTable
            .selectAll()
            .where { ClientRedirectUrisTable.clientId eq clientPk }
            .map { it[ClientRedirectUrisTable.uri] }

    private fun ResultRow.toApplication(uris: List<String> = emptyList()): Application =
        Application(
            id = ApplicationId(this[ClientsTable.id]),
            tenantId = TenantId(this[ClientsTable.tenantId]),
            clientId = this[ClientsTable.clientId],
            name = this[ClientsTable.name],
            description = this[ClientsTable.description],
            accessType = this[ClientsTable.accessType],
            enabled = this[ClientsTable.enabled],
            redirectUris = uris,
            tokenExpiryOverride = this[ClientsTable.tokenExpiryOverride],
        )
}
