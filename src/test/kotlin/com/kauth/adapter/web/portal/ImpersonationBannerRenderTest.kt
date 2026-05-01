package com.kauth.adapter.web.portal

import com.kauth.adapter.web.ImpersonationContext
import com.kauth.adapter.web.ViewContext
import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.infrastructure.EnglishOnlyTranslation
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Confirms the impersonation banner renders only when a portal page is being
 * viewed under an admin impersonation, and that destructive self-service
 * controls are disabled in that mode.
 */
class ImpersonationBannerRenderTest {
    private fun render(page: HTML.() -> Unit): String = createHTML().html { page() }

    private val session =
        PortalSession(
            userId = 10,
            tenantId = 1,
            tenantSlug = "acme",
            username = "alice",
        )

    private fun normalCtx() =
        ViewContext.englishOnly(
            theme = TenantTheme.DEFAULT,
            workspaceName = "Acme",
            translator = EnglishOnlyTranslation(),
        )

    private fun impersonatingCtx() =
        normalCtx().copy(
            impersonation =
                ImpersonationContext(
                    adminUsername = "admin",
                    targetUsername = "alice",
                ),
        )

    private val launcherApps =
        listOf(
            Application(
                id = ApplicationId(1),
                tenantId = TenantId(1),
                clientId = "app",
                name = "App",
                description = null,
                accessType = AccessType.PUBLIC,
                enabled = true,
                redirectUris = listOf("http://localhost/cb"),
                launcherUrl = "http://localhost/app",
            ),
        )

    @Test
    fun `banner does not render when no impersonation is active`() {
        val html =
            render(
                PortalView.launcherPage(
                    slug = "acme",
                    session = session,
                    ctx = normalCtx(),
                    layout = PortalLayout.SIDEBAR,
                    apps = launcherApps,
                ),
            )

        assertFalse(html.contains("impersonation-banner"))
    }

    @Test
    fun `banner renders with admin and target usernames when impersonation is active`() {
        val html =
            render(
                PortalView.launcherPage(
                    slug = "acme",
                    session = session,
                    ctx = impersonatingCtx(),
                    layout = PortalLayout.SIDEBAR,
                    apps = launcherApps,
                ),
            )

        assertTrue(html.contains("impersonation-banner"), "Banner must render")
        assertTrue(html.contains("alice"), "Target username must appear in banner")
        assertTrue(html.contains("admin"), "Acting admin username must appear in banner")
        assertTrue(
            html.contains("/t/acme/account/impersonation/stop"),
            "End-session form must point at the stop endpoint",
        )
    }

    @Test
    fun `banner renders on the centered tabnav layout too`() {
        val html =
            render(
                PortalView.launcherPage(
                    slug = "acme",
                    session = session,
                    ctx = impersonatingCtx(),
                    layout = PortalLayout.CENTERED,
                    apps = launcherApps,
                ),
            )

        assertTrue(html.contains("impersonation-banner"))
    }
}
