package com.kauth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceSettingsUpdateTest {
    @Test
    fun `from copies every editable field off the tenant`() {
        val tenant =
            Tenant(
                id = TenantId(1),
                slug = "acme",
                displayName = "Acme Corp",
                issuerUrl = "https://acme.example",
                tokenExpirySeconds = 7200,
                refreshTokenExpirySeconds = 90000,
                registrationEnabled = true,
                emailVerificationRequired = true,
                securityConfig =
                    SecurityConfig(
                        passwordMinLength = 12,
                        passwordRequireSpecial = true,
                        passwordRequireUppercase = true,
                        passwordRequireNumber = true,
                        passwordHistoryCount = 5,
                        passwordMaxAgeDays = 90,
                        passwordBlacklistEnabled = true,
                        mfaPolicy = "required",
                        lockoutMaxAttempts = 5,
                        lockoutDurationMinutes = 30,
                        corsAllowCredentials = true,
                        hibpCheckEnabled = true,
                        magicLinkEnabled = true,
                        magicLinkTokenTtlMinutes = 20,
                        passwordLoginEnabled = false,
                        emailOtpSignupEnabled = true,
                        emailOtpLockoutThreshold = 10,
                        emailOtpLoginEnabled = true,
                    ),
            )

        val u = WorkspaceSettingsUpdate.from(tenant)

        assertEquals("Acme Corp", u.displayName)
        assertEquals("https://acme.example", u.issuerUrl)
        assertEquals(7200L, u.tokenExpirySeconds)
        assertEquals(90000L, u.refreshTokenExpirySeconds)
        assertEquals(true, u.registrationEnabled)
        assertEquals(true, u.emailVerificationRequired)
        assertEquals(12, u.passwordPolicyMinLength)
        assertEquals(true, u.passwordPolicyRequireSpecial)
        assertEquals(true, u.passwordPolicyRequireUppercase)
        assertEquals(true, u.passwordPolicyRequireNumber)
        assertEquals(5, u.passwordPolicyHistoryCount)
        assertEquals(90, u.passwordPolicyMaxAgeDays)
        assertEquals(true, u.passwordPolicyBlacklistEnabled)
        assertEquals("required", u.mfaPolicy)
        assertEquals(5, u.lockoutMaxAttempts)
        assertEquals(30, u.lockoutDurationMinutes)
        assertEquals(true, u.corsAllowCredentials)
        assertEquals(true, u.hibpCheckEnabled)
        assertEquals(true, u.magicLinkEnabled)
        assertEquals(20, u.magicLinkTokenTtlMinutes)
        assertEquals(false, u.passwordLoginEnabled)
        assertEquals(true, u.emailOtpSignupEnabled)
        assertEquals(10, u.emailOtpLockoutThreshold)
        assertEquals(true, u.emailOtpLoginEnabled)
    }
}
