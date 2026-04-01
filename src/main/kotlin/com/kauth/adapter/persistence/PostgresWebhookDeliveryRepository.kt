package com.kauth.adapter.persistence

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.WebhookDelivery
import com.kauth.domain.model.WebhookDeliveryStatus
import com.kauth.domain.model.WebhookEventType
import com.kauth.domain.port.WebhookDeliveryRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

// =============================================================================
// Table definition
// =============================================================================

object WebhookDeliveriesTable : Table("webhook_deliveries") {
    val id = integer("id").autoIncrement()
    val endpointId = integer("endpoint_id") references WebhookEndpointsTable.id
    val eventType = varchar("event_type", 64)
    val payload = jsonb("payload") // JSONB column — requires PGobject wrapper
    val status = varchar("status", 16).default("pending")
    val attempts = integer("attempts").default(0)
    val lastAttemptAt = timestampWithTimeZone("last_attempt_at").nullable()
    val responseStatus = integer("response_status").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

// =============================================================================
// Repository
// =============================================================================

class PostgresWebhookDeliveryRepository : WebhookDeliveryRepository {
    override fun save(delivery: WebhookDelivery): WebhookDelivery =
        transaction {
            val insertedId =
                WebhookDeliveriesTable.insert {
                    it[endpointId] = delivery.endpointId
                    it[eventType] = delivery.eventType.value
                    it[payload] = delivery.payload
                    it[status] = delivery.status.value
                    it[attempts] = delivery.attempts
                    it[lastAttemptAt] =
                        delivery.lastAttemptAt?.let { ts ->
                            OffsetDateTime.ofInstant(ts, ZoneOffset.UTC)
                        }
                    it[responseStatus] = delivery.responseStatus
                    it[createdAt] = OffsetDateTime.ofInstant(delivery.createdAt, ZoneOffset.UTC)
                } get WebhookDeliveriesTable.id

            delivery.copy(id = insertedId)
        }

    override fun update(delivery: WebhookDelivery) =
        transaction {
            WebhookDeliveriesTable.update({ WebhookDeliveriesTable.id eq delivery.id!! }) {
                it[status] = delivery.status.value
                it[attempts] = delivery.attempts
                it[lastAttemptAt] =
                    delivery.lastAttemptAt?.let { ts ->
                        OffsetDateTime.ofInstant(ts, ZoneOffset.UTC)
                    }
                it[responseStatus] = delivery.responseStatus
            }
            Unit
        }

    override fun findByEndpointId(
        endpointId: Int,
        limit: Int,
    ): List<WebhookDelivery> =
        transaction {
            WebhookDeliveriesTable
                .selectAll()
                .where { WebhookDeliveriesTable.endpointId eq endpointId }
                .orderBy(WebhookDeliveriesTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toDelivery() }
        }

    override fun findByTenantId(
        tenantId: TenantId,
        limit: Int,
    ): List<WebhookDelivery> =
        transaction {
            // Join to endpoint table to filter by tenant; selectAll() returns columns from both tables.
            // toDelivery() only reads WebhookDeliveriesTable columns so the extra endpoint columns are safe.
            (WebhookDeliveriesTable innerJoin WebhookEndpointsTable)
                .selectAll()
                .where { WebhookEndpointsTable.tenantId eq tenantId.value }
                .orderBy(WebhookDeliveriesTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toDelivery() }
        }

    override fun findById(id: Int): WebhookDelivery? =
        transaction {
            WebhookDeliveriesTable
                .selectAll()
                .where { WebhookDeliveriesTable.id eq id }
                .map { it.toDelivery() }
                .singleOrNull()
        }

    override fun findPending(limit: Int): List<WebhookDelivery> =
        transaction {
            WebhookDeliveriesTable
                .selectAll()
                .where { WebhookDeliveriesTable.status eq WebhookDeliveryStatus.PENDING.value }
                .orderBy(WebhookDeliveriesTable.createdAt, SortOrder.ASC)
                .limit(limit)
                .map { it.toDelivery() }
        }

    // -------------------------------------------------------------------------
    // Mapper
    // -------------------------------------------------------------------------

    private fun ResultRow.toDelivery(): WebhookDelivery {
        val lastAttempt: OffsetDateTime? = this[WebhookDeliveriesTable.lastAttemptAt]
        val created: OffsetDateTime = this[WebhookDeliveriesTable.createdAt]
        return WebhookDelivery(
            id = this[WebhookDeliveriesTable.id],
            endpointId = this[WebhookDeliveriesTable.endpointId],
            eventType =
                WebhookEventType.fromValue(this[WebhookDeliveriesTable.eventType])
                    ?: WebhookEventType.USER_CREATED,
            payload = this[WebhookDeliveriesTable.payload],
            status = WebhookDeliveryStatus.fromValue(this[WebhookDeliveriesTable.status]),
            attempts = this[WebhookDeliveriesTable.attempts],
            lastAttemptAt = lastAttempt?.toInstant(),
            responseStatus = this[WebhookDeliveriesTable.responseStatus],
            createdAt = created.toInstant(),
        )
    }
}
