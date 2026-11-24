package com.kauth.adapter.persistence

import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.TenantId
import com.kauth.infrastructure.DatabaseFactory
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresApiKeyRepositoryIntegrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private val repo = PostgresApiKeyRepository()

    @BeforeAll
    fun startDb() {
        postgres = PostgreSQLContainer("postgres:15-alpine")
        postgres.start()
        DatabaseFactory.init(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password)
    }

    @AfterAll
    fun stopDb() {
        postgres.stop()
    }

    private fun masterTenantId(): TenantId =
        TenantId(
            transaction {
                TenantsTable
                    .selectAll()
                    .where { TenantsTable.slug eq "master" }
                    .single()[TenantsTable.id]
            },
        )

    private fun key(
        tenantId: TenantId,
        name: String,
        bootstrapName: String?,
    ) = repo.save(
        ApiKey(
            tenantId = tenantId,
            name = name,
            keyPrefix = "kauth_test",
            keyHash = "hash-$name",
            scopes = listOf(ApiScope.AUTH_SEND_OTP),
            enabled = true,
            bootstrapName = bootstrapName,
        ),
    )

    @Test
    fun `revoking with releaseBootstrap disables the key and drops its env lock`() {
        val tenantId = masterTenantId()
        val bootstrapped = key(tenantId, "env-key", bootstrapName = "env-key")
        val manual = key(tenantId, "console-key", bootstrapName = null)

        repo.revoke(bootstrapped.id!!, tenantId, releaseBootstrap = true)

        val released = repo.findById(bootstrapped.id!!, tenantId)!!
        assertFalse(released.enabled)
        assertNull(released.bootstrapName)
        assertEquals(true, repo.findById(manual.id!!, tenantId)!!.enabled)

        repo.revoke(manual.id!!, tenantId)
        assertFalse(repo.findById(manual.id!!, tenantId)!!.enabled)
    }
}
