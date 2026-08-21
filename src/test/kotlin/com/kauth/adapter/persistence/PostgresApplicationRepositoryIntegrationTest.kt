package com.kauth.adapter.persistence

import com.kauth.domain.model.GrantType
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

/**
 * Verifies [PostgresApplicationRepository.findByTenantId] batches redirect URIs and grant types
 * across all listed applications without cross-wiring rows between them — the risk introduced
 * by replacing N per-application lookups with two batched, grouped queries.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresApplicationRepositoryIntegrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private val repo = PostgresApplicationRepository()

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

    @Test
    fun `findByTenantId associates each application's own redirect uris and grants, not another's`() {
        val masterTenantId =
            transaction {
                TenantsTable
                    .selectAll()
                    .where { TenantsTable.slug eq "master" }
                    .single()[TenantsTable.id]
            }

        val appA =
            repo.create(
                tenantId = TenantId(masterTenantId),
                clientId = "batch-test-app-a",
                name = "App A",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://a.example.com/one", "https://a.example.com/two"),
                grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
                clientSecretHash = null,
                audience = null,
            )
        val appB =
            repo.create(
                tenantId = TenantId(masterTenantId),
                clientId = "batch-test-app-b",
                name = "App B",
                description = null,
                accessType = "confidential",
                redirectUris = listOf("https://b.example.com/callback"),
                grantTypes = setOf(GrantType.CLIENT_CREDENTIALS, GrantType.REFRESH_TOKEN),
                clientSecretHash = "hash",
                audience = null,
            )

        val listed = repo.findByTenantId(TenantId(masterTenantId))
        val listedA = listed.single { it.clientId == "batch-test-app-a" }
        val listedB = listed.single { it.clientId == "batch-test-app-b" }

        assertEquals(appA.redirectUris, listedA.redirectUris)
        assertEquals(appA.grantTypes, listedA.grantTypes)
        assertEquals(appB.redirectUris, listedB.redirectUris)
        assertEquals(appB.grantTypes, listedB.grantTypes)

        // Cross-wiring guard — batching must not leak one application's rows into another's.
        assertFalse(listedA.redirectUris.any { it in listedB.redirectUris })
        assertFalse(GrantType.AUTHORIZATION_CODE in listedB.grantTypes)
        assertFalse(GrantType.CLIENT_CREDENTIALS in listedA.grantTypes)
    }
}
