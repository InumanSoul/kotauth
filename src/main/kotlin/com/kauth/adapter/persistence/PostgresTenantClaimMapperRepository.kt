package com.kauth.adapter.persistence

import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.TenantClaimMapperRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class PostgresTenantClaimMapperRepository : TenantClaimMapperRepository {
    override fun findAll(tenantId: TenantId): List<TenantClaimMapper> =
        transaction {
            TenantClaimMappersTable
                .selectAll()
                .where { TenantClaimMappersTable.tenantId eq tenantId.value }
                .orderBy(TenantClaimMappersTable.attributeKey to SortOrder.ASC)
                .map { it.toMapper() }
        }

    override fun upsert(mapper: TenantClaimMapper) {
        transaction {
            val existing =
                TenantClaimMappersTable
                    .selectAll()
                    .where {
                        (TenantClaimMappersTable.tenantId eq mapper.tenantId.value) and
                            (TenantClaimMappersTable.attributeKey eq mapper.attributeKey)
                    }.any()

            if (existing) {
                TenantClaimMappersTable.update({
                    (TenantClaimMappersTable.tenantId eq mapper.tenantId.value) and
                        (TenantClaimMappersTable.attributeKey eq mapper.attributeKey)
                }) {
                    it[claimName] = mapper.claimName
                    it[includeInAccess] = mapper.includeInAccess
                    it[includeInId] = mapper.includeInId
                }
            } else {
                TenantClaimMappersTable.insert {
                    it[tenantId] = mapper.tenantId.value
                    it[attributeKey] = mapper.attributeKey
                    it[claimName] = mapper.claimName
                    it[includeInAccess] = mapper.includeInAccess
                    it[includeInId] = mapper.includeInId
                }
            }
        }
    }

    override fun delete(
        tenantId: TenantId,
        attributeKey: String,
    ): Boolean =
        transaction {
            TenantClaimMappersTable.deleteWhere {
                (TenantClaimMappersTable.tenantId eq tenantId.value) and
                    (TenantClaimMappersTable.attributeKey eq attributeKey)
            } > 0
        }

    private fun ResultRow.toMapper(): TenantClaimMapper =
        TenantClaimMapper(
            tenantId = TenantId(this[TenantClaimMappersTable.tenantId]),
            attributeKey = this[TenantClaimMappersTable.attributeKey],
            claimName = this[TenantClaimMappersTable.claimName],
            includeInAccess = this[TenantClaimMappersTable.includeInAccess],
            includeInId = this[TenantClaimMappersTable.includeInId],
        )
}
