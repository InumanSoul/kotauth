package com.kauth.infrastructure

import com.kauth.adapter.persistence.TenantsTable
import com.kauth.adapter.persistence.UsersTable
import com.kauth.adapter.token.BcryptPasswordHasher
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Infrastructure concern: database connection pool and schema initialization.
 *
 * Initialization order:
 *   1. Run Flyway migrations — creates / evolves the schema in a versioned, auditable way.
 *   2. Create a HikariCP connection pool and connect Exposed to it.
 *   3. Seed the master-tenant admin user only on a fresh database.
 *
 * SchemaUtils.createMissingTablesAndColumns is intentionally removed — it is
 * development-only tooling that cannot handle ALTER TABLE or constraint changes.
 * Flyway owns the schema from here on.
 */
object DatabaseFactory {
    fun init(
        url: String,
        user: String,
        password: String,
        poolMaxSize: Int = 10,
        poolMinIdle: Int = 2,
    ) {
        // Step 1: run versioned SQL migrations from src/main/resources/db/migration/
        Flyway
            .configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        // Step 2: create HikariCP pool and connect Exposed
        val dataSource = createDataSource(url, user, password, poolMaxSize, poolMinIdle)
        Database.connect(dataSource)

        // Step 3: seed the default admin user inside the master tenant (first boot only)
        transaction {
            seedAdminIfEmpty()
        }
    }

    private fun createDataSource(
        url: String,
        user: String,
        password: String,
        poolMaxSize: Int,
        poolMinIdle: Int,
    ): HikariDataSource {
        val config =
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                driverClassName = "org.postgresql.Driver"

                // Pool sizing — for coroutine-based apps, size to actual DB concurrency
                // needs (CPU cores × 2), not thread count. Default 10 is safe for
                // single-instance and small multi-instance deployments (4 × 10 = 40,
                // well within PostgreSQL's default max_connections = 100).
                maximumPoolSize = poolMaxSize
                minimumIdle = poolMinIdle

                // How long a caller waits for a connection from the pool before timing out.
                connectionTimeout = 3_000

                // How long an idle connection stays in the pool before eviction.
                idleTimeout = 300_000 // 5 minutes

                // Hard ceiling on connection age — prevents stale connections that were
                // silently terminated by firewalls or network devices.
                maxLifetime = 600_000 // 10 minutes

                // Periodic keepalive prevents idle connections from being killed by
                // cloud load balancers or PG idle_in_transaction_session_timeout.
                keepaliveTime = 60_000 // 1 minute

                // Logs a warning with stack trace if a connection is held longer than
                // this — invaluable for catching N+1 queries and slow admin operations.
                leakDetectionThreshold = 4_000 // 4 seconds

                // Connection validation
                connectionTestQuery = "SELECT 1"

                // Pool name for logging and JMX
                poolName = "kotauth-pg"
            }
        return HikariDataSource(config)
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
            val masterTenantId =
                TenantsTable
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
