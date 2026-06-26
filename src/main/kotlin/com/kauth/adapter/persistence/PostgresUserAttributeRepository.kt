package com.kauth.adapter.persistence

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserAttribute
import com.kauth.domain.model.UserId
import com.kauth.domain.port.UserAttributeRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

class PostgresUserAttributeRepository : UserAttributeRepository {
    override fun findAll(
        userId: UserId,
        tenantId: TenantId,
    ): Map<String, String> =
        transaction {
            UserAttributesTable
                .selectAll()
                .where {
                    (UserAttributesTable.userId eq userId.value) and
                        (UserAttributesTable.tenantId eq tenantId.value)
                }.associate { it[UserAttributesTable.key] to it[UserAttributesTable.value] }
        }

    override fun upsert(attribute: UserAttribute) {
        transaction {
            val updatedAt = OffsetDateTime.ofInstant(attribute.updatedAt, ZoneOffset.UTC)
            val existing =
                UserAttributesTable
                    .selectAll()
                    .where {
                        (UserAttributesTable.userId eq attribute.userId.value) and
                            (UserAttributesTable.key eq attribute.key)
                    }.any()

            if (existing) {
                UserAttributesTable.update({
                    (UserAttributesTable.userId eq attribute.userId.value) and
                        (UserAttributesTable.key eq attribute.key)
                }) {
                    it[value] = attribute.value
                    it[this.updatedAt] = updatedAt
                    it[tenantId] = attribute.tenantId.value
                }
            } else {
                UserAttributesTable.insert {
                    it[userId] = attribute.userId.value
                    it[tenantId] = attribute.tenantId.value
                    it[key] = attribute.key
                    it[value] = attribute.value
                    it[this.updatedAt] = updatedAt
                }
            }
        }
    }

    override fun delete(
        userId: UserId,
        tenantId: TenantId,
        key: String,
    ): Boolean =
        transaction {
            UserAttributesTable.deleteWhere {
                (UserAttributesTable.userId eq userId.value) and
                    (UserAttributesTable.tenantId eq tenantId.value) and
                    (UserAttributesTable.key eq key)
            } > 0
        }

    override fun deleteAllForUser(
        userId: UserId,
        tenantId: TenantId,
    ) {
        transaction {
            UserAttributesTable.deleteWhere {
                (UserAttributesTable.userId eq userId.value) and
                    (UserAttributesTable.tenantId eq tenantId.value)
            }
        }
    }

    @Suppress("unused")
    private fun ResultRow.toUserAttribute(): UserAttribute =
        UserAttribute(
            userId = UserId(this[UserAttributesTable.userId]),
            tenantId = TenantId(this[UserAttributesTable.tenantId]),
            key = this[UserAttributesTable.key],
            value = this[UserAttributesTable.value],
            updatedAt = this[UserAttributesTable.updatedAt].toInstant(),
        )
}
