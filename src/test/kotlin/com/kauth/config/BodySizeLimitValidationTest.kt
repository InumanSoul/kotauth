package com.kauth.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the fail-fast predicate behind `KAUTH_MAX_REQUEST_BODY_BYTES` and
 * `KAUTH_MAX_BACKUP_IMPORT_BODY_BYTES` validation. `EnvironmentConfig.load()` itself reads real
 * process env vars and calls `exitProcess(1)` on failure — not unit-testable without killing the
 * test JVM, and no other FATAL validator in this file is unit tested either (only `envOrFile`,
 * which is side-effect-free). [isValidBodySizeLimit] is the pure predicate `load()` calls before
 * exiting, factored out specifically so the boundary logic itself has coverage.
 */
class BodySizeLimitValidationTest {
    @Test
    fun `zero is invalid`() {
        assertFalse(isValidBodySizeLimit(0))
    }

    @Test
    fun `a negative value is invalid`() {
        assertFalse(isValidBodySizeLimit(-1))
    }

    @Test
    fun `a value above Int MAX_VALUE is invalid`() {
        assertFalse(isValidBodySizeLimit(Int.MAX_VALUE.toLong() + 1))
    }

    @Test
    fun `Int MAX_VALUE itself is valid`() {
        assertTrue(isValidBodySizeLimit(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun `one byte is valid`() {
        assertTrue(isValidBodySizeLimit(1))
    }

    @Test
    fun `the documented defaults are valid`() {
        assertTrue(isValidBodySizeLimit(2 * 1024 * 1024L))
        assertTrue(isValidBodySizeLimit(100 * 1024 * 1024L))
    }
}
