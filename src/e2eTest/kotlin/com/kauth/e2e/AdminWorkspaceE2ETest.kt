package com.kauth.e2e

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
import kotlin.test.assertTrue

class AdminWorkspaceE2ETest : E2ETestBase() {
    @Test
    fun `create workspace redirects to workspace detail`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/new")
        page.fill("input[name=slug]", "acme-corp")
        page.fill("input[name=displayName]", "Acme Corp")
        page.locator("button[type=submit][form=create-workspace-form]").click()

        page.waitForURL(
            Pattern.compile(".*/workspaces/acme-corp"),
            Page
                .WaitForURLOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(5000.0),
        )
        val url = page.url()
        assertTrue(
            url.endsWith("/workspaces/acme-corp"),
            "URL must end with workspace slug, got: $url",
        )
        assertTrue(
            !url.contains("TenantId("),
            "URL must not contain TenantId toString, got: $url",
        )
    }

    @Test
    fun `workspace list page does not leak value class toString`() {
        tenantRepo.add(
            Tenant(
                id = TenantId(2),
                slug = "demo-ws",
                displayName = "Demo Workspace",
                issuerUrl = null,
                theme = TenantTheme.DEFAULT,
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces")

        val content = page.content()
        assertTrue(
            !content.contains("TenantId(value="),
            "Page must not render TenantId value class toString",
        )
    }

    @Test
    fun `workspace detail with apps does not leak ApplicationId`() {
        appRepo.add(
            Application(
                id = ApplicationId(5),
                tenantId = TenantId(1),
                clientId = "web-client",
                name = "Web Client",
                description = null,
                accessType = AccessType.PUBLIC,
                enabled = true,
            ),
        )
        appRepo.add(
            Application(
                id = ApplicationId(6),
                tenantId = TenantId(1),
                clientId = "api-server",
                name = "API Server",
                description = null,
                accessType = AccessType.CONFIDENTIAL,
                enabled = true,
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/master")

        val content = page.content()
        assertTrue(
            !content.contains("ApplicationId(value="),
            "Page must not render ApplicationId value class toString",
        )
        assertTrue(
            !content.contains("TenantId(value="),
            "Page must not render TenantId value class toString",
        )
        assertTrue(content.contains("web-client"), "Should show app clientId")
        assertTrue(content.contains("api-server"), "Should show app clientId")
    }

    @Test
    fun `workspace detail app links use string clientId not numeric ID`() {
        appRepo.add(
            Application(
                id = ApplicationId(5),
                tenantId = TenantId(1),
                clientId = "my-frontend",
                name = "My Frontend",
                description = null,
                accessType = AccessType.PUBLIC,
                enabled = true,
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/master")

        val appLinks = page.querySelectorAll("a[href*='/applications/']")
        appLinks.forEach { link ->
            val href = link.getAttribute("href") ?: ""
            if (href.contains("/applications/") && !href.endsWith("/new")) {
                assertTrue(
                    !href.matches(Regex(".*/applications/\\d+$")),
                    "App link must use string clientId not numeric ID, got: $href",
                )
                assertTrue(
                    !href.contains("ApplicationId("),
                    "App link must not contain ApplicationId toString, got: $href",
                )
            }
        }
    }
}
