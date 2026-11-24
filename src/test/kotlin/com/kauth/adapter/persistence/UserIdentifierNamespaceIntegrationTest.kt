package com.kauth.adapter.persistence

import com.kauth.infrastructure.DatabaseFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** V70: usernames and emails share one namespace per workspace, enforced by Postgres. */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserIdentifierNamespaceIntegrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private var tenantId = 0

    @BeforeAll
    fun startDb() {
        postgres = PostgreSQLContainer("postgres:15-alpine")
        postgres.start()
        DatabaseFactory.init(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password)
        connect().use { c ->
            tenantId = c.single("SELECT id FROM tenants WHERE slug = 'master'").toInt()
        }
    }

    @AfterAll
    fun stopDb() = postgres.stop()

    @BeforeEach
    fun clearTestUsers() {
        connect().use { it.exec("DELETE FROM users WHERE username LIKE 'ns-%' OR email LIKE '%@ns.test'") }
    }

    private fun connect(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.single(sql: String): String =
        createStatement().use { st ->
            st.executeQuery(sql).use {
                it.next()
                it.getString(1)
            }
        }

    private fun Connection.insertUser(
        username: String,
        email: String,
    ): Int =
        prepareStatement(
            "INSERT INTO users (tenant_id, username, email, password_hash, full_name) VALUES (?, ?, ?, 'x', 'N') RETURNING id",
        ).use { st ->
            st.setInt(1, tenantId)
            st.setString(2, username)
            st.setString(3, email)
            st.executeQuery().use {
                it.next()
                it.getInt(1)
            }
        }

    private fun Connection.holderOf(identifier: String): String =
        single(
            "SELECT coalesce(max(user_id)::text, 'none') FROM user_identifiers WHERE tenant_id = $tenantId AND identifier = '$identifier'",
        )

    @Test
    fun `an email cannot be another user's username, nor the other way round`() {
        connect().use { c ->
            c.insertUser("ns-ana@ns.test", "ana.real@ns.test")
            c.insertUser("ns-bob", "bob@ns.test")

            val emailTaken = assertFailsWith<SQLException> { c.insertUser("ns-carl", "ns-ana@ns.test") }
            val usernameTaken = assertFailsWith<SQLException> { c.insertUser("bob@ns.test", "dan@ns.test") }

            assertEquals("23505", emailTaken.sqlState)
            assertEquals("23505", usernameTaken.sqlState)
        }
    }

    @Test
    fun `a username may be the user's own email`() {
        connect().use { c ->
            val id = c.insertUser("ns-self@ns.test", "ns-self@ns.test")

            assertEquals(id.toString(), c.holderOf("ns-self@ns.test"))
        }
    }

    @Test
    fun `renaming frees the old identifier and claims the new one`() {
        connect().use { c ->
            val id = c.insertUser("ns-old", "old@ns.test")

            c.exec("UPDATE users SET username = 'ns-new', email = 'new@ns.test' WHERE id = $id")
            c.insertUser("ns-other", "ns-old")

            assertEquals(id.toString(), c.holderOf("ns-new"))
            assertEquals(id.toString(), c.holderOf("new@ns.test"))
            assertEquals("none", c.holderOf("old@ns.test"))
            assertFailsWith<SQLException> { c.exec("UPDATE users SET email = 'ns-new' WHERE username = 'ns-other'") }
        }
    }

    @Test
    fun `of two concurrent writers of mirrored identifiers only one commits`() {
        connect().use { first ->
            connect().use { second ->
                first.autoCommit = false
                second.autoCommit = false
                first.insertUser("ns-race@ns.test", "race-a@ns.test")

                val secondWrite =
                    CompletableFuture.supplyAsync {
                        runCatching { second.insertUser("ns-race-b", "ns-race@ns.test").also { second.commit() } }
                    }
                Thread.sleep(300)
                assertTrue(!secondWrite.isDone, "the second writer waits on the first")
                first.commit()

                val outcome = secondWrite.get(10, TimeUnit.SECONDS)
                second.rollback()
                assertEquals("23505", (outcome.exceptionOrNull() as SQLException).sqlState)
            }
        }
    }

    @Test
    fun `a pair from before V70 keeps working and the shared identifier passes on when released`() {
        connect().use { c ->
            c.exec("ALTER TABLE users DISABLE TRIGGER users_claim_identifiers")
            val holder = c.insertUser("ns-shared@ns.test", "holder@ns.test")
            val other = c.insertUser("ns-legacy", "ns-shared@ns.test")
            c.exec("ALTER TABLE users ENABLE TRIGGER users_claim_identifiers")
            c.exec("DELETE FROM user_identifiers WHERE user_id IN ($holder, $other)")
            c.exec(
                "INSERT INTO user_identifiers VALUES ($tenantId, 'ns-shared@ns.test', $holder), " +
                    "($tenantId, 'holder@ns.test', $holder), ($tenantId, 'ns-legacy', $other)",
            )

            c.exec("UPDATE users SET full_name = 'Renamed', username = 'ns-legacy-2' WHERE id = $other")
            c.exec("DELETE FROM users WHERE id = $holder")

            assertEquals(other.toString(), c.holderOf("ns-shared@ns.test"))
            assertEquals(other.toString(), c.holderOf("ns-legacy-2"))
            assertEquals("none", c.holderOf("ns-legacy"))
        }
    }
}
