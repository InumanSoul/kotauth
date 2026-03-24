package com.kauth.e2e

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminSettingsE2ETest : E2ETestBase() {
    private val workspace =
        Tenant(
            id = TenantId(2),
            slug = "settings-ws",
            displayName = "Settings Workspace",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    @BeforeEach
    fun seedWorkspace() {
        tenantRepo.add(workspace)
    }

    @Test
    fun `general settings page does not leak value class toString`() {
        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/settings-ws/settings")

        val content = page.content()
        assertFalse(
            content.contains("TenantId(value="),
            "Settings page must not render TenantId value class toString",
        )
        assertTrue(
            content.contains("Settings Workspace") || content.contains("settings-ws"),
            "Settings page must render workspace name or slug",
        )
    }

    @Test
    fun `settings save redirects with saved param`() {
        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/settings-ws/settings")

        page.fill("input[name=displayName]", "Updated Workspace")
        page.locator("button[type=submit][form=general-form]").click()

        page.waitForURL(
            Pattern.compile(".*/settings\\?saved=true"),
            Page
                .WaitForURLOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(5000.0),
        )
        assertTrue(page.url().contains("saved=true"), "Redirect must include saved=true param")
    }

    @Test
    fun `smtp settings page does not leak value class toString`() {
        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/settings-ws/settings/smtp")

        val content = page.content()
        assertFalse(
            content.contains("TenantId(value="),
            "SMTP page must not render TenantId value class toString",
        )
        assertTrue(
            content.contains("SMTP") || content.contains("smtp") || content.contains("Email"),
            "SMTP page must contain SMTP-related content",
        )
    }

    @Test
    fun `security policy page does not leak value class toString`() {
        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/settings-ws/settings/security")

        val content = page.content()
        assertFalse(
            content.contains("TenantId(value="),
            "Security page must not render TenantId value class toString",
        )
        assertTrue(
            content.contains("Security") || content.contains("Password") || content.contains("password"),
            "Security page must contain security-related content",
        )
    }
}
