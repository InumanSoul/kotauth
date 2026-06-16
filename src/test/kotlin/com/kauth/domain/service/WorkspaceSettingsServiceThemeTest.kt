package com.kauth.domain.service

import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeThemeRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkspaceSettingsServiceThemeTest {
    private val tenants = FakeTenantRepository()
    private val themes = FakeThemeRepository()
    private val auditLog = FakeAuditLogPort()

    private val svc =
        WorkspaceSettingsService(
            tenantRepository = tenants,
            auditLog = auditLog,
            themeRepository = themes,
        )

    @BeforeTest
    fun setup() {
        tenants.clear()
        themes.clear()
        auditLog.clear()
        tenants.add(
            Tenant(
                id = TenantId(1),
                slug = "acme",
                displayName = "Acme",
                issuerUrl = null,
                securityConfig = SecurityConfig(),
            ),
        )
    }

    @Test
    fun `updateTheme rejects malicious color`() {
        val result = svc.updateTheme("acme", TenantTheme.DEFAULT.copy(accentColor = "#fff; injection }"))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateTheme rejects malicious fontFamily`() {
        val result = svc.updateTheme("acme", TenantTheme.DEFAULT.copy(fontFamily = "Inter}; expression()"))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateTheme rejects malicious borderRadius`() {
        val result = svc.updateTheme("acme", TenantTheme.DEFAULT.copy(borderRadius = "8px; evil"))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateTheme rejects javascript scheme in logoUrl`() {
        val result = svc.updateTheme("acme", TenantTheme.DEFAULT.copy(logoUrl = "javascript:alert(1)"))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateTheme accepts a valid theme and persists it`() {
        val theme =
            TenantTheme.DEFAULT.copy(
                accentColor = "#0A6EBD",
                logoUrl = "https://cdn.example.com/logo.svg",
                defaultLocale = "en-US",
            )
        val result = svc.updateTheme("acme", theme)
        assertIs<AdminResult.Success<TenantTheme>>(result)
        assertEquals("#0A6EBD", result.value.accentColor)
    }
}
