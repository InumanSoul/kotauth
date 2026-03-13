package com.kauth.adapter.persistence

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.port.ApplicationRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Persistence adapter — implements ApplicationRepository using PostgreSQL + Exposed.
 *
 * Reads from ClientsTable + ClientRedirectUrisTable (V2 migration schema).
 * Follows the same pattern as PostgresTenantRepository.
 */
class PostgresApplicationRepository : ApplicationRepository {

    override fun findByTenantId(tenantId: Int): List<Application> = transaction {
        ClientsTable.selectAll()
            .where { ClientsTable.tenantId eq tenantId }
            .orderBy(ClientsTable.id)
            .map { row ->
                val uris = urisForClientPk(row[ClientsTable.id])
                row.toApplication(uris)
            }
    }

    override fun findByClientId(tenantId: Int, clientId: String): Application? = transaction {
        val row = ClientsTable.selectAll()
            .where { (ClientsTable.tenantId eq tenantId) and (ClientsTable.clientId eq clientId) }
            .singleOrNull() ?: return@transaction null
        val uris = urisForClientPk(row[ClientsTable.id])
        row.toApplication(uris)
    }

    override fun existsByClientId(tenantId: Int, clientId: String): Boolean = transaction {
        ClientsTable.selectAll()
            .where { (ClientsTable.tenantId eq tenantId) and (ClientsTable.clientId eq clientId) }
            .count() > 0
    }

    override fun create(
        tenantId: Int,
        clientId: String,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>
    ): Application = transaction {
        val insertedPk = ClientsTable.insert {
            it[ClientsTable.tenantId]    = tenantId
            it[ClientsTable.clientId]    = clientId
            it[ClientsTable.name]        = name
            it[ClientsTable.description] = description
            it[ClientsTable.accessType]  = AccessType.fromValue(accessType)
        } get ClientsTable.id

        if (redirectUris.isNotEmpty()) {
            ClientRedirectUrisTable.batchInsert(redirectUris) { uri ->
                this[ClientRedirectUrisTable.clientId] = insertedPk
                this[ClientRedirectUrisTable.uri]      = uri
            }
        }

        val row  = ClientsTable.selectAll().where { ClientsTable.id eq insertedPk }.single()
        val uris = urisForClientPk(insertedPk)
        row.toApplication(uris)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun urisForClientPk(clientPk: Int): List<String> =
        ClientRedirectUrisTable.selectAll()
            .where { ClientRedirectUrisTable.clientId eq clientPk }
            .map { it[ClientRedirectUrisTable.uri] }

    private fun ResultRow.toApplication(uris: List<String> = emptyList()): Application = Application(
        id           = this[ClientsTable.id],
        tenantId     = this[ClientsTable.tenantId],
        clientId     = this[ClientsTable.clientId],
        name         = this[ClientsTable.name],
        description  = this[ClientsTable.description],
        accessType   = this[ClientsTable.accessType],
        enabled      = this[ClientsTable.enabled],
        redirectUris = uris
    )
}
