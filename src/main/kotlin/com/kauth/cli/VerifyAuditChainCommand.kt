package com.kauth.cli

import com.kauth.adapter.persistence.AuditLogTable
import com.kauth.adapter.persistence.TenantsTable
import com.kauth.config.DbConfig
import com.kauth.infrastructure.AuditChainHasher
import com.kauth.infrastructure.DatabaseFactory
import com.kauth.infrastructure.toHex
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.system.exitProcess

object VerifyAuditChainCommand {
    fun execute(args: List<String>) {
        val tenantSlug = parseArg(args, "--tenant")
        val fromId = parseArg(args, "--from-id")?.toIntOrNull()
        val quiet = "--quiet" in args

        val secretKey = System.getenv("KAUTH_SECRET_KEY")
        if (secretKey == null) {
            System.err.println("KAUTH_SECRET_KEY is required")
            exitProcess(2)
        }

        val hasher = AuditChainHasher(secretKey)
        val dbConfig = DbConfig.load()
        DatabaseFactory.connectOnly(dbConfig)

        val exit =
            transaction {
                runVerification(hasher, tenantSlug, fromId, quiet)
            }

        exitProcess(exit)
    }

    internal fun runVerification(
        hasher: AuditChainHasher,
        tenantSlug: String?,
        fromId: Int?,
        quiet: Boolean,
    ): Int {
        val tenantIds: List<Int?> =
            if (tenantSlug != null) {
                val id =
                    TenantsTable
                        .selectAll()
                        .where { TenantsTable.slug eq tenantSlug }
                        .firstOrNull()
                        ?.get(TenantsTable.id)
                if (id == null) {
                    System.err.println("Tenant not found: $tenantSlug")
                    return 2
                }
                listOf(id)
            } else {
                AuditLogTable
                    .select(AuditLogTable.tenantId)
                    .groupBy(AuditLogTable.tenantId)
                    .map { it[AuditLogTable.tenantId] }
            }

        if (tenantIds.isEmpty()) {
            if (!quiet) println("No audit_log rows found.")
            return 2
        }

        var anyDivergence = false

        for (tid in tenantIds) {
            val diverged = verifyTenant(hasher, tid, fromId, quiet)
            if (diverged) anyDivergence = true
        }

        if (!anyDivergence && !quiet) println("Chain OK")
        return if (anyDivergence) 1 else 0
    }

    internal fun verifyTenant(
        hasher: AuditChainHasher,
        tenantId: Int?,
        fromId: Int?,
        quiet: Boolean,
    ): Boolean {
        val label = tenantId?.toString() ?: "system"
        var previousHash: ByteArray? = null
        var diverged = false

        val query =
            AuditLogTable
                .selectAll()
                .where {
                    if (tenantId == null) AuditLogTable.tenantId.isNull() else AuditLogTable.tenantId eq tenantId
                }.orderBy(AuditLogTable.id, SortOrder.ASC)

        if (fromId != null) {
            query.andWhere { AuditLogTable.id greaterEq fromId }
        }

        var rowCount = 0
        for (row in query) {
            rowCount++
            val id = row[AuditLogTable.id]
            val storedRowHash = row[AuditLogTable.rowHash]

            if (storedRowHash == null) {
                if (!quiet) println("tenant=$label id=$id: pre-chain (no row_hash, skipped)")
                previousHash = null
                continue
            }

            val storedPrevHash = row[AuditLogTable.prevHash]

            if (previousHash != null && !storedPrevHash.contentEquals(previousHash)) {
                System.err.println("CHAIN BREAK at tenant=$label id=$id: prev_hash mismatch")
                diverged = true
                break
            }

            val canonical =
                hasher.canonicalize(
                    id = id,
                    tenantId = row[AuditLogTable.tenantId],
                    userId = row[AuditLogTable.userId],
                    clientId = row[AuditLogTable.clientId],
                    eventType = row[AuditLogTable.eventType],
                    ipAddress = row[AuditLogTable.ipAddress],
                    userAgent = row[AuditLogTable.userAgent],
                    createdAt = row[AuditLogTable.createdAt],
                    detailsJson = row[AuditLogTable.details],
                    prevHash = storedPrevHash,
                )
            val expected = hasher.hmac(canonical)

            if (!expected.contentEquals(storedRowHash)) {
                System.err.println(
                    "TAMPERED at tenant=$label id=$id: row_hash mismatch (expected ${expected.toHex()}, got ${storedRowHash.toHex()})",
                )
                diverged = true
                break
            }

            previousHash = storedRowHash
        }

        if (rowCount == 0 && !quiet) println("tenant=$label: no rows")
        return diverged
    }

    private fun parseArg(
        args: List<String>,
        prefix: String,
    ): String? =
        args.firstNotNullOfOrNull { arg ->
            if (arg.startsWith("$prefix=")) arg.removePrefix("$prefix=").takeIf { it.isNotBlank() } else null
        }
}
