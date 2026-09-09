package com.kauth.infrastructure

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks the migration files without starting a database, so these failures land in `make test`
 * rather than after a container has booted.
 *
 * The case worth catching is two branches independently adding the same version number. Flyway
 * refuses to run in that state, but only once someone applies it — and by then the second file is
 * already merged. Reading the filenames catches it in seconds, on every PR.
 */
class MigrationNamingTest {
    @Test
    fun `no two migrations claim the same version number`() {
        val duplicates = MigrationFiles.duplicateVersions()
        assertTrue(
            duplicates.isEmpty(),
            "each migration version must be unique — two branches adding the same number is the " +
                "usual cause. Duplicates: $duplicates",
        )
    }

    @Test
    fun `every migration follows the V-number-double-underscore-snake-case convention`() {
        val malformed = MigrationFiles.malformedNames()
        assertTrue(
            malformed.isEmpty(),
            "migration filenames must match V<number>__<lower_snake_case>.sql. Offenders: $malformed",
        )
    }

    @Test
    fun `migration versions are contiguous from 1`() {
        val versions = MigrationFiles.versionsOnDisk().sorted()
        assertTrue(versions.isNotEmpty(), "there must be migrations on disk")

        val gaps = (1..versions.max()).filterNot { it in versions.toSet() }
        assertTrue(
            gaps.isEmpty(),
            "a missing version usually means a migration was deleted or never merged. Gaps: $gaps",
        )
    }
}
