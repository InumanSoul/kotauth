package com.kauth.adapter.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Exposed mapping for 'user_attributes' (V33 migration).
 * Schema is Flyway-owned — no constraint declarations here.
 */
object UserAttributesTable : Table("user_attributes") {
    val userId = integer("user_id")
    val tenantId = integer("tenant_id")
    val key = varchar("key", 64)
    val value = text("value")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(userId, key)
}
