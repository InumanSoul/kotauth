package com.kauth.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminBootstrapPasswordTest {
    @Test
    fun `env password takes precedence over demo and generator`() {
        val resolution =
            AdminBootstrapPassword.resolve(
                envPassword = "OperatorSecret123",
                isDemoMode = true,
                generator = { error("generator should not run when env is set") },
            )

        assertEquals(AdminBootstrapPassword.Source.ENV, resolution.source)
        assertEquals("OperatorSecret123", resolution.password)
    }

    @Test
    fun `blank env password falls through to next source`() {
        val resolution =
            AdminBootstrapPassword.resolve(
                envPassword = "   ",
                isDemoMode = true,
                generator = { "should-not-be-used" },
            )

        assertEquals(AdminBootstrapPassword.Source.DEMO, resolution.source)
        assertEquals(AdminBootstrapPassword.DEMO_PASSWORD, resolution.password)
    }

    @Test
    fun `demo mode uses the fixed demo password when env is unset`() {
        val resolution =
            AdminBootstrapPassword.resolve(
                envPassword = null,
                isDemoMode = true,
                generator = { error("generator should not run in demo mode") },
            )

        assertEquals(AdminBootstrapPassword.Source.DEMO, resolution.source)
        assertEquals(AdminBootstrapPassword.DEMO_PASSWORD, resolution.password)
    }

    @Test
    fun `non-demo non-env path invokes the generator`() {
        val resolution =
            AdminBootstrapPassword.resolve(
                envPassword = null,
                isDemoMode = false,
                generator = { "generated-token" },
            )

        assertEquals(AdminBootstrapPassword.Source.GENERATED, resolution.source)
        assertEquals("generated-token", resolution.password)
    }

    @Test
    fun `default generator produces a 32-char hex string`() {
        val resolution = AdminBootstrapPassword.resolve(envPassword = null, isDemoMode = false)

        assertEquals(AdminBootstrapPassword.Source.GENERATED, resolution.source)
        assertEquals(32, resolution.password.length)
        assertTrue(resolution.password.all { it in "0123456789abcdef" })
    }

    @Test
    fun `validate rejects passwords shorter than 12 chars`() {
        val error = AdminBootstrapPassword.validateOperatorPassword("Abc123!")
        assertNotNull(error)
        assertTrue(error.contains("12 characters"))
    }

    @Test
    fun `validate rejects passwords without an uppercase letter`() {
        assertNotNull(AdminBootstrapPassword.validateOperatorPassword("nouppercase123"))
    }

    @Test
    fun `validate rejects passwords without a lowercase letter`() {
        assertNotNull(AdminBootstrapPassword.validateOperatorPassword("NOLOWERCASE123"))
    }

    @Test
    fun `validate rejects passwords without a digit`() {
        assertNotNull(AdminBootstrapPassword.validateOperatorPassword("NoDigitsHereOk"))
    }

    @Test
    fun `validate accepts a strong password`() {
        assertNull(AdminBootstrapPassword.validateOperatorPassword("OperatorSecret123"))
    }
}
