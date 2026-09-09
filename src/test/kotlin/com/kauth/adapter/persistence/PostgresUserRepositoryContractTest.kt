package com.kauth.adapter.persistence

import com.kauth.domain.model.TenantId
import com.kauth.domain.port.UserRepository
import com.kauth.infrastructure.DatabaseFactory
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

/**
 * [UserRepositoryContract] against a real Postgres.
 *
 * This is the half that matters: the fake and the adapter agreed on everything the unit suite
 * could observe while `update()` silently dropped the `username` column. Running the same
 * assertions against both is what makes that class of divergence visible.
 *
 * Tagged `postgres` — run with `make test-postgres`, not part of `make test`.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresUserRepositoryContractTest : UserRepositoryContract() {
    private lateinit var postgres: PostgreSQLContainer<*>

    override val repo: UserRepository = PostgresUserRepository()

    @BeforeAll
    fun startDb() {
        postgres = PostgreSQLContainer("postgres:15-alpine")
        postgres.start()
        DatabaseFactory.init(
            url = postgres.jdbcUrl,
            user = postgres.username,
            password = postgres.password,
        )
    }

    @AfterAll
    fun stopDb() {
        postgres.stop()
    }

    override fun tenantId(): TenantId =
        TenantId(
            transaction {
                TenantsTable
                    .selectAll()
                    .where { TenantsTable.slug eq "master" }
                    .single()[TenantsTable.id]
            },
        )
}
