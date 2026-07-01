package com.kauth.adapter.persistence

import com.kauth.domain.port.CorsPolicy
import com.kauth.domain.port.CorsPort
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI

class PostgresCorsAdapter : CorsPort {
    override fun policyForTenant(tenantSlug: String): CorsPolicy? =
        transaction {
            val tenantRow =
                TenantsTable
                    .join(
                        TenantSecurityConfigTable,
                        JoinType.LEFT,
                        additionalConstraint = { TenantsTable.id eq TenantSecurityConfigTable.tenantId },
                    ).selectAll()
                    .where { TenantsTable.slug eq tenantSlug }
                    .singleOrNull() ?: return@transaction null

            val tenantId = tenantRow[TenantsTable.id]
            val allowCredentials =
                tenantRow.getOrNull(TenantSecurityConfigTable.corsAllowCredentials) ?: false

            val origins =
                ClientRedirectUrisTable
                    .join(
                        ClientsTable,
                        JoinType.INNER,
                        additionalConstraint = {
                            ClientRedirectUrisTable.clientId eq ClientsTable.id
                        },
                    ).selectAll()
                    .where { (ClientsTable.tenantId eq tenantId) and (ClientsTable.enabled eq true) }
                    .mapNotNull { row -> row[ClientRedirectUrisTable.uri].toOriginOrNull() }
                    .toSet()

            CorsPolicy(allowedOrigins = origins, allowCredentials = allowCredentials)
        }

    private fun String.toOriginOrNull(): String? =
        runCatching {
            val uri = URI(this)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host ?: return null
            val port = uri.port
            if (port < 0 || port == defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:$port"
        }.getOrNull()

    private fun defaultPort(scheme: String): Int =
        when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
}
