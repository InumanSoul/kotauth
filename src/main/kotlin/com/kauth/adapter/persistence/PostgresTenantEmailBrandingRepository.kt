package com.kauth.adapter.persistence

import com.kauth.domain.model.TenantEmailBranding
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.TenantEmailBrandingRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

object TenantEmailBrandingTable : Table("tenant_email_branding") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id") references TenantsTable.id
    val brandName = varchar("brand_name", 128).nullable()
    val brandColorHex = varchar("brand_color_hex", 7).nullable()
    val brandLogoUrl = text("brand_logo_url").nullable()
    val supportEmail = varchar("support_email", 255).nullable()
    val fromDisplayName = varchar("from_display_name", 128).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

class PostgresTenantEmailBrandingRepository : TenantEmailBrandingRepository {
    override fun findByTenantId(tenantId: TenantId): TenantEmailBranding? =
        transaction {
            TenantEmailBrandingTable
                .selectAll()
                .where { TenantEmailBrandingTable.tenantId eq tenantId.value }
                .map { it.toBranding() }
                .singleOrNull()
        }

    override fun upsert(branding: TenantEmailBranding): TenantEmailBranding =
        transaction {
            val now = Instant.now().toOffsetDateTime()
            val updated =
                TenantEmailBrandingTable.update({
                    TenantEmailBrandingTable.tenantId eq branding.tenantId.value
                }) {
                    it[brandName] = branding.brandName
                    it[brandColorHex] = branding.brandColorHex
                    it[brandLogoUrl] = branding.brandLogoUrl
                    it[supportEmail] = branding.supportEmail
                    it[fromDisplayName] = branding.fromDisplayName
                    it[updatedAt] = now
                }
            if (updated == 0) {
                TenantEmailBrandingTable.insert {
                    it[tenantId] = branding.tenantId.value
                    it[brandName] = branding.brandName
                    it[brandColorHex] = branding.brandColorHex
                    it[brandLogoUrl] = branding.brandLogoUrl
                    it[supportEmail] = branding.supportEmail
                    it[fromDisplayName] = branding.fromDisplayName
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            findByTenantId(branding.tenantId)!!
        }

    override fun deleteByTenantId(tenantId: TenantId) =
        transaction {
            TenantEmailBrandingTable.deleteWhere {
                TenantEmailBrandingTable.tenantId eq tenantId.value
            }
            Unit
        }

    private fun ResultRow.toBranding() =
        TenantEmailBranding(
            id = this[TenantEmailBrandingTable.id],
            tenantId = TenantId(this[TenantEmailBrandingTable.tenantId]),
            brandName = this[TenantEmailBrandingTable.brandName],
            brandColorHex = this[TenantEmailBrandingTable.brandColorHex],
            brandLogoUrl = this[TenantEmailBrandingTable.brandLogoUrl],
            supportEmail = this[TenantEmailBrandingTable.supportEmail],
            fromDisplayName = this[TenantEmailBrandingTable.fromDisplayName],
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
