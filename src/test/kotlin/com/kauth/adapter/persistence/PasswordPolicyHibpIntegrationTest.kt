package com.kauth.adapter.persistence

import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeBreachedPasswordPort
import com.kauth.fakes.FakePasswordHasher
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the HIBP breach-check wiring in [PostgresPasswordPolicyAdapter.validate].
 *
 * Keeps `passwordHistoryCount = 0` and `passwordBlacklistEnabled = false` so the
 * validate path never touches Exposed (no live DB needed).
 */
class PasswordPolicyHibpIntegrationTest {
    private val hasher = FakePasswordHasher()
    private val breachChecker = FakeBreachedPasswordPort()

    private val adapter =
        PostgresPasswordPolicyAdapter(
            passwordHasher = hasher,
            breachedPasswordChecker = breachChecker,
        )

    private fun tenantWith(hibpEnabled: Boolean = false): Tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            securityConfig =
                SecurityConfig(
                    passwordMinLength = 4, // easy to satisfy without noise
                    passwordHistoryCount = 0,
                    passwordBlacklistEnabled = false,
                    hibpCheckEnabled = hibpEnabled,
                ),
        )

    @BeforeTest
    fun setup() {
        breachChecker.clear()
    }

    @Test
    fun `validate skips HIBP check when tenant toggle is off`() {
        breachChecker.breachedPasswords.add("password123")

        val result = adapter.validate("password123", tenantWith(hibpEnabled = false), userId = null)
        assertNull(result)
        assertEquals(0, breachChecker.callCount)
    }

    @Test
    fun `validate calls HIBP when tenant toggle is on`() {
        val result = adapter.validate("safe-password", tenantWith(hibpEnabled = true), userId = null)
        assertNull(result)
        assertEquals(1, breachChecker.callCount)
    }

    @Test
    fun `validate returns an error message when HIBP reports the password as breached`() {
        breachChecker.breachedPasswords.add("password123")

        val result = adapter.validate("password123", tenantWith(hibpEnabled = true), userId = null)
        assertNotNull(result)
        assertTrue(result.contains("data breach"))
        // Error message must be neutral — no mention of HIBP/provider names
        assertTrue(!result.contains("HIBP"))
        assertTrue(!result.contains("pwned"))
    }

    @Test
    fun `validate with no breach checker wired through returns null - feature off`() {
        val noCheckerAdapter = PostgresPasswordPolicyAdapter(passwordHasher = hasher)

        val result = noCheckerAdapter.validate("password123", tenantWith(hibpEnabled = true), userId = null)
        assertNull(result)
    }

    @Test
    fun `fail-open - breach checker error does not block the password`() {
        breachChecker.simulateError = true
        breachChecker.breachedPasswords.add("password123")

        val result = adapter.validate("password123", tenantWith(hibpEnabled = true), userId = null)
        assertNull(result, "Fail-open: errors should not block the password")
    }

    @Test
    fun `validate still enforces length before HIBP check`() {
        breachChecker.breachedPasswords.add("abc")

        val result = adapter.validate("abc", tenantWith(hibpEnabled = true), userId = null)
        assertNotNull(result)
        assertTrue(result.contains("at least"))
        // Length check runs first — HIBP is never consulted for an invalid-length password
        assertEquals(0, breachChecker.callCount)
    }
}
