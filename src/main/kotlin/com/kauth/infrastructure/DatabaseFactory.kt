package com.kauth.infrastructure

import com.kauth.adapter.persistence.RolesTable
import com.kauth.adapter.persistence.TenantsTable
import com.kauth.adapter.persistence.UserRolesTable
import com.kauth.adapter.persistence.UsersTable
import com.kauth.adapter.token.BcryptPasswordHasher
import com.kauth.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object DatabaseFactory {
    fun connectOnly(config: DbConfig) {
        val dataSource = createDataSource(config.dbUrl, config.dbUser, config.dbPassword, 2, 1)
        Database.connect(dataSource)
    }

    fun init(
        url: String,
        user: String,
        password: String,
        poolMaxSize: Int = 10,
        poolMinIdle: Int = 2,
        bootstrapAdminPassword: String? = null,
        isDemoMode: Boolean = false,
    ) {
        Flyway
            .configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = createDataSource(url, user, password, poolMaxSize, poolMinIdle)
        Database.connect(dataSource)

        transaction {
            seedAdminIfEmpty(
                AdminBootstrapPassword.resolve(bootstrapAdminPassword, isDemoMode),
            )
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

                maximumPoolSize = poolMaxSize
                minimumIdle = poolMinIdle

                connectionTimeout = 3_000
                idleTimeout = 300_000
                maxLifetime = 600_000
                keepaliveTime = 60_000
                leakDetectionThreshold = 4_000

                connectionTestQuery = "SELECT 1"
                poolName = "kotauth-pg"
            }
        return HikariDataSource(config)
    }

    private fun seedAdminIfEmpty(resolution: AdminBootstrapPassword.Resolution) {
        if (!UsersTable.selectAll().empty()) return

        val masterTenantId =
            TenantsTable
                .selectAll()
                .where { TenantsTable.slug eq "master" }
                .firstOrNull()
                ?.get(TenantsTable.id)
                ?: error("Master tenant not found — V1 migration may not have run correctly.")

        val hasher = BcryptPasswordHasher()
        val adminUserId =
            UsersTable.insert {
                it[tenantId] = masterTenantId
                it[username] = "admin"
                it[email] = "admin@kauth.local"
                it[passwordHash] = hasher.hash(resolution.password)
                it[fullName] = "KotAuth Administrator"
                it[emailVerified] = false
                it[enabled] = true
            } get UsersTable.id

        announceSeededAdmin(resolution)
        assignAdminRole(masterTenantId, adminUserId)
    }

    private fun announceSeededAdmin(resolution: AdminBootstrapPassword.Resolution) {
        when (resolution.source) {
            AdminBootstrapPassword.Source.ENV ->
                System.err.println("[INFO] Seeded admin user from KAUTH_BOOTSTRAP_ADMIN_PASSWORD.")
            AdminBootstrapPassword.Source.DEMO ->
                System.err.println(
                    "[INFO] Seeded admin user with the documented demo password. " +
                        "KAUTH_DEMO_MODE=true must never be used in production.",
                )
            AdminBootstrapPassword.Source.GENERATED -> printGeneratedPasswordBanner(resolution.password)
        }
    }

    private fun printGeneratedPasswordBanner(password: String) {
        val box =
            """

            ┌──────────────────────────────────────────────────────────────┐
            │  Initial admin password (shown ONCE — store it now):         │
            │      username: admin                                         │
            │      password: $password
            │  Rotate it from Profile → Security after first login.        │
            │  To skip this banner, set KAUTH_BOOTSTRAP_ADMIN_PASSWORD.    │
            └──────────────────────────────────────────────────────────────┘

            """.trimIndent()
        println(box)
        System.err.println("[WARN] Generated initial admin password — see stdout banner above.")
    }

    private fun assignAdminRole(
        masterTenantId: Int,
        adminUserId: Int,
    ) {
        val adminRoleId =
            RolesTable
                .selectAll()
                .where {
                    (RolesTable.tenantId eq masterTenantId) and
                        (RolesTable.name eq "admin") and
                        (RolesTable.scope eq "tenant")
                }.firstOrNull()
                ?.get(RolesTable.id) ?: return

        val alreadyAssigned =
            UserRolesTable
                .selectAll()
                .where {
                    (UserRolesTable.userId eq adminUserId) and
                        (UserRolesTable.roleId eq adminRoleId)
                }.count() > 0

        if (alreadyAssigned) return

        UserRolesTable.insert {
            it[userId] = adminUserId
            it[roleId] = adminRoleId
            it[assignedAt] = java.time.OffsetDateTime.now()
        }
    }
}
