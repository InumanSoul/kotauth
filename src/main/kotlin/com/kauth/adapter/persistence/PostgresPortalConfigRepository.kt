package com.kauth.adapter.persistence

import com.kauth.domain.model.PortalConfig
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.PortalConfigRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

/**
 * Persistence adapter — implements [PortalConfigRepository] using PostgreSQL + Exposed.
 */
class PostgresPortalConfigRepository : PortalConfigRepository {
    override fun findByTenantId(tenantId: TenantId): PortalConfig? =
        transaction {
            WorkspacePortalConfigTable
                .selectAll()
                .where { WorkspacePortalConfigTable.tenantId eq tenantId.value }
                .map { it.toPortalConfig() }
                .singleOrNull()
        }

    override fun upsert(
        tenantId: TenantId,
        config: PortalConfig,
    ): PortalConfig =
        transaction {
            val exists =
                WorkspacePortalConfigTable
                    .selectAll()
                    .where { WorkspacePortalConfigTable.tenantId eq tenantId.value }
                    .count() > 0

            if (exists) {
                WorkspacePortalConfigTable.update(
                    { WorkspacePortalConfigTable.tenantId eq tenantId.value },
                ) {
                    it[layout] = config.layout.name
                    it[updatedAt] = OffsetDateTime.now()
                }
            } else {
                WorkspacePortalConfigTable.insert {
                    it[WorkspacePortalConfigTable.tenantId] = tenantId.value
                    it[layout] = config.layout.name
                    it[createdAt] = OffsetDateTime.now()
                    it[updatedAt] = OffsetDateTime.now()
                }
            }
            config
        }

    private fun ResultRow.toPortalConfig(): PortalConfig =
        PortalConfig(
            layout =
                runCatching {
                    PortalLayout.valueOf(this[WorkspacePortalConfigTable.layout])
                }.getOrDefault(PortalLayout.SIDEBAR),
        )
}
