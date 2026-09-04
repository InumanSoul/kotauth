package com.kauth.cli

import com.kauth.adapter.persistence.MfaEnrollmentsTable
import com.kauth.adapter.persistence.MfaRecoveryCodesTable
import com.kauth.adapter.persistence.TenantsTable
import com.kauth.adapter.persistence.UsersTable
import com.kauth.config.DbConfig
import com.kauth.domain.service.UsernamePolicy
import com.kauth.infrastructure.DatabaseFactory
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.system.exitProcess

object ResetAdminMfaCommand {
    fun execute(args: List<String>) {
        // Usernames are always stored normalized (see UsernamePolicy) — this break-glass lookup
        // must match the same way, or an operator's `USER=Admin` fails after V66 rewrites the row.
        val username = parseUsername(args)?.let { UsernamePolicy.normalize(it) }
        if (username == null) {
            System.err.println("Usage: cli reset-admin-mfa --username=<admin-username>")
            exitProcess(1)
        }

        val dbConfig = DbConfig.load()
        DatabaseFactory.connectOnly(dbConfig)

        transaction {
            val masterTenantId =
                TenantsTable
                    .selectAll()
                    .where { TenantsTable.slug eq "master" }
                    .firstOrNull()
                    ?.get(TenantsTable.id)
                    ?: run {
                        System.err.println("Master tenant not found. Has the database been initialized?")
                        exitProcess(1)
                    }

            val userRow =
                UsersTable
                    .selectAll()
                    .where { (UsersTable.tenantId eq masterTenantId) and (UsersTable.username eq username) }
                    .firstOrNull()
                    ?: run {
                        System.err.println("No user '$username' found on the master tenant.")
                        exitProcess(1)
                    }

            val userId = userRow[UsersTable.id]

            val enrollmentsDeleted = MfaEnrollmentsTable.deleteWhere { MfaEnrollmentsTable.userId eq userId }
            val codesDeleted = MfaRecoveryCodesTable.deleteWhere { MfaRecoveryCodesTable.userId eq userId }

            UsersTable.update({ UsersTable.id eq userId }) {
                it[mfaEnabled] = false
            }

            println(
                "MFA reset for '$username': " +
                    "$enrollmentsDeleted enrollment(s) and $codesDeleted recovery code(s) removed.",
            )
            println("The user can re-enroll MFA on their next login.")
        }

        exitProcess(0)
    }

    private fun parseUsername(args: List<String>): String? =
        args.firstNotNullOfOrNull { arg ->
            when {
                arg.startsWith("--username=") -> arg.removePrefix("--username=").takeIf { it.isNotBlank() }
                else -> null
            }
        }
}
