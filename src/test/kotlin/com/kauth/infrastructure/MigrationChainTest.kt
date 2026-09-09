package com.kauth.infrastructure

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Applies every migration to an empty database.
 *
 * The unit suite runs on in-memory fakes and cannot see a migration at all. The other Postgres
 * tests reach the latest schema only as a side effect of `DatabaseFactory.init`, and assert on
 * repository behaviour rather than on the migration chain — so a broken `V*.sql` passed Flyway's
 * checksum validation, passed the unit tests, and only failed when someone provisioned a fresh
 * deployment. That has happened here before (V55, v1.20.1).
 *
 * Applying a migration to an *existing* database is checked by hand before a release, which covers
 * the upgrade path. This covers the fresh path.
 *
 * Deliberately does not assert the resulting schema in detail: reaching the newest version without
 * error is most of the value, and a schema snapshot would need updating on every migration whether
 * or not anything was wrong.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationChainTest {
    private lateinit var postgres: PostgreSQLContainer<*>

    @BeforeAll
    fun startDb() {
        // Deliberately NOT DatabaseFactory.init — that also seeds an admin and connects Exposed.
        // This test is about the migration chain alone, on a database nothing else has touched.
        postgres = PostgreSQLContainer("postgres:15-alpine")
        postgres.start()
    }

    @AfterAll
    fun stopDb() {
        postgres.stop()
    }

    private fun flyway() =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()

    @Test
    fun `every migration applies to an empty database, reaches the newest version, and re-runs clean`() {
        // One test rather than two: both assertions describe the same run sequence against the
        // same database, and splitting them would depend on JUnit method order — the container is
        // shared, so whichever ran first would migrate it out from under the other.
        val first = flyway().migrate()

        assertTrue(first.success, "migration run must succeed")

        val expected = MigrationFiles.versionsOnDisk()
        assertEquals(
            expected.size,
            first.migrationsExecuted,
            "every migration on disk must be applied to an empty database",
        )
        assertEquals(
            expected.max().toString(),
            first.targetSchemaVersion,
            "the chain must reach the newest migration on disk",
        )

        val failed = flyway().info().all().filter { it.state?.isFailed == true }
        assertTrue(failed.isEmpty(), "no migration may be left in a failed state: $failed")

        // Re-running against an already-migrated database must apply nothing. This is what an
        // operator's restart does on every deploy.
        val second = flyway().migrate()
        assertEquals(0, second.migrationsExecuted, "a second run must be a no-op")
    }
}
