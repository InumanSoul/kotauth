package com.kauth.adapter.persistence

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.PasswordPolicyPort
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Postgres implementation of [PasswordPolicyPort].
 *
 * Validates passwords against tenant-configured policies:
 *   - Minimum length
 *   - Require special characters
 *   - Require uppercase letters
 *   - Require numbers
 *   - Password history (no reuse of last N)
 *   - Blacklist (common passwords)
 */
class PostgresPasswordPolicyAdapter(
    private val passwordHasher: PasswordHasher,
) : PasswordPolicyPort {
    override fun validate(
        rawPassword: String,
        tenant: Tenant,
        userId: Int?,
    ): String? {
        // Length check
        if (rawPassword.length < tenant.passwordPolicyMinLength) {
            return "Password must be at least ${tenant.passwordPolicyMinLength} characters."
        }

        // Special character check
        if (tenant.passwordPolicyRequireSpecial && !rawPassword.any { !it.isLetterOrDigit() }) {
            return "Password must contain at least one special character."
        }

        // Uppercase check
        if (tenant.passwordPolicyRequireUppercase && !rawPassword.any { it.isUpperCase() }) {
            return "Password must contain at least one uppercase letter."
        }

        // Number check
        if (tenant.passwordPolicyRequireNumber && !rawPassword.any { it.isDigit() }) {
            return "Password must contain at least one number."
        }

        // Blacklist check
        if (tenant.passwordPolicyBlacklistEnabled && isBlacklisted(rawPassword, tenant.id)) {
            return "This password is too common. Please choose a different one."
        }

        // History check (only for existing users changing password)
        if (userId != null && tenant.passwordPolicyHistoryCount > 0) {
            if (isInHistory(userId, tenant.id, rawPassword, tenant.passwordPolicyHistoryCount)) {
                return "You cannot reuse any of your last ${tenant.passwordPolicyHistoryCount} passwords."
            }
        }

        return null // valid
    }

    override fun recordPasswordHistory(
        userId: Int,
        tenantId: Int,
        passwordHash: String,
    ): Unit =
        transaction {
            PasswordHistoryTable.insert {
                it[this.userId] = userId
                it[this.tenantId] = tenantId
                it[this.passwordHash] = passwordHash
                it[this.createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }

    override fun isInHistory(
        userId: Int,
        tenantId: Int,
        rawPassword: String,
        historyCount: Int,
    ): Boolean =
        transaction {
            val recentHashes =
                PasswordHistoryTable
                    .selectAll()
                    .where {
                        (PasswordHistoryTable.userId eq userId) and
                            (PasswordHistoryTable.tenantId eq tenantId)
                    }.orderBy(PasswordHistoryTable.createdAt, SortOrder.DESC)
                    .limit(historyCount)
                    .map { it[PasswordHistoryTable.passwordHash] }

            recentHashes.any { passwordHasher.verify(rawPassword, it) }
        }

    override fun isBlacklisted(
        rawPassword: String,
        tenantId: Int,
    ): Boolean =
        transaction {
            val normalised = rawPassword.lowercase()
            PasswordBlacklistTable
                .selectAll()
                .where {
                    (PasswordBlacklistTable.password eq normalised) and
                        (PasswordBlacklistTable.tenantId.isNull() or (PasswordBlacklistTable.tenantId eq tenantId))
                }.count() > 0
        }
}
