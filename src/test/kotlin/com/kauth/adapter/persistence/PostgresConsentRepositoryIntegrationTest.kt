package com.kauth.adapter.persistence

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.infrastructure.DatabaseFactory
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V74 against a real database: the consent table, the application switch and scope descriptions. */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresConsentRepositoryIntegrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private val consents = PostgresConsentRepository()
    private val apps = PostgresApplicationRepository()
    private val apis = PostgresResourceServerRepository()
    private val users = PostgresUserRepository()

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

    private fun master(): TenantId =
        TenantId(
            transaction {
                TenantsTable.selectAll().where { TenantsTable.slug eq "master" }.single()[TenantsTable.id]
            },
        )

    private fun app(
        clientId: String,
        consentRequired: Boolean,
    ) = apps.create(
        tenantId = master(),
        clientId = clientId,
        name = clientId,
        description = null,
        accessType = "public",
        redirectUris = listOf("https://$clientId.example.com/cb"),
        grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
        clientSecretHash = null,
        audience = null,
        consentRequired = consentRequired,
    )

    private fun user(name: String): UserId =
        users
            .save(
                User(
                    tenantId = master(),
                    username = name,
                    email = "$name@example.com",
                    fullName = name,
                    passwordHash = "x",
                ),
            ).id!!

    @Test
    fun `a grant is created, widened, listed and deleted per user and application`() {
        val tenant = master()
        val alice = user("consent-alice")
        val bob = user("consent-bob")
        val partner = app("consent-partner", consentRequired = true)
        val at = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        consents.save(tenant, alice, partner.id, setOf("openid"), at)
        val widened = consents.save(tenant, alice, partner.id, setOf("openid", "email"), at.plusSeconds(60))

        assertEquals(setOf("openid", "email"), widened.scopes)
        assertEquals(at, widened.createdAt, "widening keeps the original grant time")
        assertEquals(at.plusSeconds(60), widened.updatedAt)
        assertEquals(listOf(partner.id), consents.findByUser(tenant, alice).map { it.applicationId })
        assertNull(consents.find(tenant, bob, partner.id), "another user's grant is separate")

        assertTrue(consents.delete(tenant, alice, partner.id))
        assertFalse(consents.delete(tenant, alice, partner.id))
        assertNull(consents.find(tenant, alice, partner.id))
    }

    @Test
    fun `the consent switch is stored on create and on update`() {
        val created = app("consent-switch", consentRequired = true)
        assertTrue(apps.findById(created.id)!!.consentRequired)

        apps.update(
            appId = created.id,
            name = created.name,
            description = null,
            accessType = "public",
            redirectUris = created.redirectUris,
            grantTypes = created.grantTypes,
            tokenExpiryOverride = null,
            consentRequired = false,
        )

        assertFalse(apps.findById(created.id)!!.consentRequired)
        assertFalse(apps.findById(ApplicationId(1))!!.consentRequired, "existing applications migrate off")
    }

    @Test
    fun `scope descriptions round-trip through the resource server row`() {
        val created =
            apis.create(
                master(),
                "https://consent-api.example.com",
                "Orders",
                null,
                scopes = listOf("orders:read"),
                scopeDescriptions = mapOf("orders:read" to "See your orders"),
            )

        assertEquals(
            mapOf("orders:read" to "See your orders"),
            apis.findById(master(), created.id!!)?.scopeDescriptions,
        )
    }
}
