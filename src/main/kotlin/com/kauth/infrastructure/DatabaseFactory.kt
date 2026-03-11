package com.kauth.infrastructure

import com.kauth.adapter.persistence.TenantsTable
import com.kauth.adapter.persistence.UsersTable
import com.kauth.adapter.token.BcryptPasswordHasher
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Infrastructure concern: database connection and schema initialization.
 *
 * Initialization order:
 *   1. Run Flyway migrations — creates / evolves the schema in a versioned, auditable way.
 *   2. Connect Exposed to the same datasource.
 *   3. Seed the master-tenant admin user only on a fresh database.
 *
 * SchemaUtils.createMissingTablesAndColumns is intentionally removed — it is
 * development-only tooling that cannot handle ALTER TABLE or constraint changes.
 * Flyway owns the schema from here on.
 */
object DatabaseFactory {

    fun init(url: String, user: String, password: String) {
        // Step 1: run versioned SQL migrations from src/main/resources/db/migration/
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        // Step 2: connect Exposed (query DSL) — Flyway already created the schema
        Database.connect(
            url = url,
            driver = "org.postgresql.Driver",
            user = user,
            password = password
        )

        // Step 3: seed the default admin user inside the master tenant (first boot only)
        transaction {
            seedAdminIfEmpty()
        }
    }

    /**
     * Seeds a default admin user into the master tenant on a fresh database.
     *
     * Resolves the master tenant from the DB (seeded by V1 migration) so the
     * admin user is correctly scoped — no hardcoded tenant_id assumptions.
     *
     * Credentials are BCrypt-hashed. The default password is intentionally weak
     * and documented — operators MUST change it before any real deployment.
     */
    private fun seedAdminIfEmpty() {
        if (UsersTable.selectAll().empty()) {
            val masterTenantId = TenantsTable
                .selectAll()
                .where { TenantsTable.slug eq "master" }
                .firstOrNull()
                ?.get(TenantsTable.id)
                ?: error("Master tenant not found — V1 migration may not have run correctly.")

            val hasher = BcryptPasswordHasher()
            UsersTable.insert {
                it[tenantId] = masterTenantId
                it[username] = "admin"
                it[email] = "admin@kauth.local"
                it[passwordHash] = hasher.hash("changeme123!")
                it[fullName] = "KotAuth Administrator"
                it[emailVerified] = false
                it[enabled] = true
            }
        }
    }
}
