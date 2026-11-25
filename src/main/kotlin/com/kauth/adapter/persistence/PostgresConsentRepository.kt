package com.kauth.adapter.persistence

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserConsent
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ConsentRepository
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.ZoneOffset

class PostgresConsentRepository : ConsentRepository {
    override fun find(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
    ): UserConsent? =
        transaction {
            UserConsentsTable
                .selectAll()
                .where { matches(tenantId, userId, applicationId) }
                .singleOrNull()
                ?.toConsent()
        }

    override fun findByUser(
        tenantId: TenantId,
        userId: UserId,
    ): List<UserConsent> =
        transaction {
            UserConsentsTable
                .selectAll()
                .where { (UserConsentsTable.tenantId eq tenantId.value) and (UserConsentsTable.userId eq userId.value) }
                .orderBy(UserConsentsTable.id)
                .map { it.toConsent() }
        }

    override fun save(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
        scopes: Set<String>,
        at: Instant,
    ): UserConsent =
        transaction {
            val stamp = at.atOffset(ZoneOffset.UTC)
            val encoded = Json.encodeToString(scopes.toList())
            val updated =
                UserConsentsTable.update({ matches(tenantId, userId, applicationId) }) {
                    it[UserConsentsTable.scopes] = encoded
                    it[UserConsentsTable.updatedAt] = stamp
                }
            if (updated == 0) {
                UserConsentsTable.insert {
                    it[UserConsentsTable.tenantId] = tenantId.value
                    it[UserConsentsTable.userId] = userId.value
                    it[UserConsentsTable.clientId] = applicationId.value
                    it[UserConsentsTable.scopes] = encoded
                    it[UserConsentsTable.createdAt] = stamp
                    it[UserConsentsTable.updatedAt] = stamp
                }
            }
            UserConsentsTable
                .selectAll()
                .where { matches(tenantId, userId, applicationId) }
                .single()
                .toConsent()
        }

    override fun delete(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
    ): Boolean =
        transaction {
            UserConsentsTable.deleteWhere { matches(tenantId, userId, applicationId) } > 0
        }

    private fun matches(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
    ): Op<Boolean> =
        (UserConsentsTable.tenantId eq tenantId.value) and
            (UserConsentsTable.userId eq userId.value) and
            (UserConsentsTable.clientId eq applicationId.value)

    private fun ResultRow.toConsent() =
        UserConsent(
            tenantId = TenantId(this[UserConsentsTable.tenantId]),
            userId = UserId(this[UserConsentsTable.userId]),
            applicationId = ApplicationId(this[UserConsentsTable.clientId]),
            scopes = Json.decodeFromString<List<String>>(this[UserConsentsTable.scopes].ifBlank { "[]" }).toSet(),
            createdAt = this[UserConsentsTable.createdAt].toInstant(),
            updatedAt = this[UserConsentsTable.updatedAt].toInstant(),
        )
}
