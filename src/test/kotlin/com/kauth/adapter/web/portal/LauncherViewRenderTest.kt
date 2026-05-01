package com.kauth.adapter.web.portal

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * View-layer tests for [PortalView.launcherPage].
 *
 * Pure rendering — no Ktor, no DB. Verifies the launcher page reflects the
 * apps list correctly and shows the empty-state copy with a clear path back
 * to the workspace admin.
 */
class LauncherViewRenderTest {
    private fun render(page: HTML.() -> Unit): String = createHTML().html { page() }

    private val session =
        PortalSession(
            userId = 10,
            tenantId = 1,
            tenantSlug = "acme",
            username = "alice",
        )

    private fun launcherApp(
        id: Int,
        name: String,
        url: String = "http://localhost/$id",
        iconUrl: String? = null,
    ): Application =
        Application(
            id = ApplicationId(id),
            tenantId = TenantId(1),
            clientId = "app-$id",
            name = name,
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("http://localhost/cb"),
            launcherUrl = url,
            iconUrl = iconUrl,
        )

    @Test
    fun `empty list renders the ask-admin guidance`() {
        val html =
            render(
                PortalView.launcherPage(
                    slug = "acme",
                    session = session,
                    theme = TenantTheme.DEFAULT,
                    workspaceName = "Acme Corp",
                    layout = PortalLayout.SIDEBAR,
                    apps = emptyList(),
                ),
            )

        assertTrue(html.contains("Ask your workspace admin"), "Empty state must guide the user to request access")
        assertTrue(html.contains("launcher-empty"), "Empty state must use the launcher-empty CSS class")
        assertFalse(html.contains("launcher-grid"), "Grid must not render when there are no apps")
    }

    @Test
    fun `apps render as tiles with name, url, and external-tab attrs`() {
        val html =
            render(
                PortalView.launcherPage(
                    slug = "acme",
                    session = session,
                    theme = TenantTheme.DEFAULT,
                    workspaceName = "Acme Corp",
                    layout = PortalLayout.SIDEBAR,
                    apps = listOf(launcherApp(100, "Tasks App", url = "http://localhost/tasks")),
                ),
            )

        assertTrue(html.contains("launcher-grid"), "Grid container must render when apps are present")
        assertTrue(html.contains("Tasks App"), "App name must appear")
        assertTrue(html.contains("href=\"http://localhost/tasks\""), "Tile must link to the launcher URL")
        assertTrue(html.contains("target=\"_blank\""), "Tile must open in a new tab")
        assertTrue(html.contains("rel=\"noopener noreferrer\""), "Tile must use rel=noopener noreferrer for security")
    }

    @Test
    fun `tile uses iconUrl image and provides text fallback for broken loads`() {
        val html =
            render(
                PortalView.launcherPage(
                    slug = "acme",
                    session = session,
                    theme = TenantTheme.DEFAULT,
                    workspaceName = "Acme Corp",
                    layout = PortalLayout.SIDEBAR,
                    apps =
                        listOf(
                            launcherApp(100, "Tasks App", iconUrl = "https://cdn.example.com/icon.svg"),
                        ),
                ),
            )

        assertTrue(html.contains("https://cdn.example.com/icon.svg"), "Icon URL must render in img src")
        assertTrue(html.contains("data-fallback=\"T\""), "Fallback initial must be set on the img tag")
        assertTrue(html.contains("onerror"), "Broken-icon onerror handler must swap to text fallback")
    }

    @Test
    fun `tile without iconUrl renders the first letter of the app name`() {
        val html =
            render(
                PortalView.launcherPage(
                    slug = "acme",
                    session = session,
                    theme = TenantTheme.DEFAULT,
                    workspaceName = "Acme Corp",
                    layout = PortalLayout.SIDEBAR,
                    apps = listOf(launcherApp(100, "Reports", iconUrl = null)),
                ),
            )

        assertTrue(html.contains("launcher-tile__icon"), "Icon container must render")
        assertFalse(html.contains("<img"), "No img tag should render when iconUrl is null")
        assertTrue(html.contains(">R<"), "First-letter fallback must render the uppercase initial")
    }

    @Test
    fun `Applications nav entry is marked active on the launcher page`() {
        val html =
            render(
                PortalView.launcherPage(
                    slug = "acme",
                    session = session,
                    theme = TenantTheme.DEFAULT,
                    workspaceName = "Acme Corp",
                    layout = PortalLayout.SIDEBAR,
                    apps = emptyList(),
                ),
            )

        assertTrue(
            html.contains("/t/acme/launcher") && html.contains("is-active"),
            "Launcher nav link must be marked active",
        )
        assertTrue(html.contains("Applications"), "Nav must include the Applications label")
    }
}
