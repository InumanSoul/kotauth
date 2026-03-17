package com.kauth.adapter.persistence

import com.kauth.domain.model.WebhookEndpoint
import com.kauth.domain.port.WebhookEndpointRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

// =============================================================================
// Table definition
// =============================================================================

object WebhookEndpointsTable : Table("webhook_endpoints") {
    val id          = integer("id").autoIncrement()
    val tenantId    = integer("tenant_id")
    val url         = varchar("url", 2048)
    val secret      = varchar("secret", 256)
    val events      = text("events")
    val description = varchar("description", 256).default("")
    val enabled     = bool("enabled").default(true)
    val createdAt   = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

// =============================================================================
// Repository
// =============================================================================

class PostgresWebhookEndpointRepository : WebhookEndpointRepository {

    override fun save(endpoint: WebhookEndpoint): WebhookEndpoint = transaction {
        val insertedId = WebhookEndpointsTable.insert {
            it[tenantId]    = endpoint.tenantId
            it[url]         = endpoint.url
            it[secret]      = endpoint.secret
            it[events]      = endpoint.events.joinToString(",")
            it[description] = endpoint.description
            it[enabled]     = endpoint.enabled
            it[createdAt]   = OffsetDateTime.ofInstant(endpoint.createdAt, ZoneOffset.UTC)
        } get WebhookEndpointsTable.id

        endpoint.copy(id = insertedId)
    }

    override fun findByTenantId(tenantId: Int): List<WebhookEndpoint> = transaction {
        WebhookEndpointsTable.selectAll()
            .where { WebhookEndpointsTable.tenantId eq tenantId }
            .orderBy(WebhookEndpointsTable.createdAt, SortOrder.DESC)
            .map { it.toEndpoint() }
    }

    override fun findEnabledByTenantAndEvent(tenantId: Int, eventType: String): List<WebhookEndpoint> = transaction {
        WebhookEndpointsTable.selectAll()
            .where {
                (WebhookEndpointsTable.tenantId eq tenantId) and
                (WebhookEndpointsTable.enabled eq true)
            }
            .map { it.toEndpoint() }
            .filter { eventType in it.events }
    }

    override fun findById(id: Int, tenantId: Int): WebhookEndpoint? = transaction {
        WebhookEndpointsTable.selectAll()
            .where { (WebhookEndpointsTable.id eq id) and (WebhookEndpointsTable.tenantId eq tenantId) }
            .map { it.toEndpoint() }
            .singleOrNull()
    }

    override fun update(endpoint: WebhookEndpoint) = transaction {
        WebhookEndpointsTable.update({
            (WebhookEndpointsTable.id eq endpoint.id!!) and
            (WebhookEndpointsTable.tenantId eq endpoint.tenantId)
        }) {
            it[url]         = endpoint.url
            it[events]      = endpoint.events.joinToString(",")
            it[description] = endpoint.description
            it[enabled]     = endpoint.enabled
        }
        Unit
    }

    override fun delete(id: Int, tenantId: Int) = transaction {
        WebhookEndpointsTable.deleteWhere {
            (WebhookEndpointsTable.id eq id) and (WebhookEndpointsTable.tenantId eq tenantId)
        }
        Unit
    }

    override fun setEnabled(id: Int, tenantId: Int, enabled: Boolean) = transaction {
        WebhookEndpointsTable.update({
            (WebhookEndpointsTable.id eq id) and (WebhookEndpointsTable.tenantId eq tenantId)
        }) {
            it[WebhookEndpointsTable.enabled] = enabled
        }
        Unit
    }

    // -------------------------------------------------------------------------
    // Mapper
    // -------------------------------------------------------------------------

    private fun ResultRow.toEndpoint(): WebhookEndpoint {
        val created: OffsetDateTime = this[WebhookEndpointsTable.createdAt]
        return WebhookEndpoint(
            id          = this[WebhookEndpointsTable.id],
            tenantId    = this[WebhookEndpointsTable.tenantId],
            url         = this[WebhookEndpointsTable.url],
            secret      = this[WebhookEndpointsTable.secret],
            events      = this[WebhookEndpointsTable.events].split(",").filter { it.isNotBlank() }.toSet(),
            description = this[WebhookEndpointsTable.description],
            enabled     = this[WebhookEndpointsTable.enabled],
            createdAt   = created.toInstant()
        )
    }
}
