package com.kauth.adapter.web.auth

import com.kauth.adapter.web.ViewContext
import com.kauth.domain.model.LoginLayout
import com.kauth.domain.model.TenantTheme
import com.kauth.infrastructure.EnglishOnlyTranslation
import kotlinx.html.HTML
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure view-layer tests for the [authShell] composite — covers the CENTERED (default)
 * and SPLIT layout variants introduced in v1.21.0.
 */
class AuthShellLayoutTest {
    private fun render(page: HTML.() -> Unit): String = createHTML().html { page() }

    private fun renderShell(
        theme: TenantTheme,
        workspaceName: String = "Acme Corp",
    ): String =
        render {
            body {
                authShell(workspaceName, theme) {
                    div("card") { +"card-content" }
                }
            }
        }

    private val viewContext =
        ViewContext.englishOnly(
            theme = TenantTheme.DEFAULT,
            workspaceName = "Acme Corp",
            translator = EnglishOnlyTranslation(),
        )

    @Test
    fun `authShell renders single-column layout when theme loginLayout is CENTERED`() {
        val html = renderShell(TenantTheme.DEFAULT.copy(loginLayout = LoginLayout.CENTERED))

        assertContains(html, "class=\"shell\"")
        assertFalse(html.contains("shell--split"), "CENTERED layout must not carry the split modifier class")
        assertFalse(html.contains("<aside"), "CENTERED layout must not render the split-panel aside")
        assertFalse(html.contains("shell__panel"), "CENTERED layout must not render the split panel")
    }

    @Test
    fun `authShell renders two-column layout when theme loginLayout is SPLIT`() {
        val html = renderShell(TenantTheme.DEFAULT.copy(loginLayout = LoginLayout.SPLIT))

        assertContains(html, "class=\"shell shell--split\"")
        assertContains(html, "<aside class=\"shell__panel\"")
        assertContains(html, "class=\"shell__form\"")
    }

    @Test
    fun `SPLIT layout uses loginTagline when set`() {
        val html =
            renderShell(
                TenantTheme.DEFAULT.copy(
                    loginLayout = LoginLayout.SPLIT,
                    loginTagline = "Welcome to Acme",
                ),
            )

        assertContains(html, "Welcome to Acme")
    }

    @Test
    fun `SPLIT layout falls back to workspace name when tagline is null`() {
        val html =
            renderShell(
                theme = TenantTheme.DEFAULT.copy(loginLayout = LoginLayout.SPLIT, loginTagline = null),
                workspaceName = "Acme Corp",
            )

        assertContains(html, "Acme Corp")
    }

    @Test
    fun `SPLIT layout applies background image when loginBackgroundUrl is set`() {
        val html =
            renderShell(
                TenantTheme.DEFAULT.copy(
                    loginLayout = LoginLayout.SPLIT,
                    loginBackgroundUrl = "https://acme.dev/hero.jpg",
                ),
            )

        assertContains(html, "background-image:url('https://acme.dev/hero.jpg')")
    }

    @Test
    fun `SPLIT layout escapes single quotes in loginBackgroundUrl`() {
        val html =
            renderShell(
                TenantTheme.DEFAULT.copy(
                    loginLayout = LoginLayout.SPLIT,
                    loginBackgroundUrl = "https://acme.dev/foo');background:red;//",
                ),
            )

        assertContains(html, "%27")
        assertFalse(html.contains("');background:red"), "single quote must not break out of the url() literal")
    }

    @Test
    fun `SPLIT layout omits background style when loginBackgroundUrl is null`() {
        val html =
            renderShell(
                TenantTheme.DEFAULT.copy(loginLayout = LoginLayout.SPLIT, loginBackgroundUrl = null),
            )

        assertFalse(html.contains("background-image:url"))
    }

    @Test
    fun `data-layout attribute matches the chosen enum in lowercase`() {
        val centeredHtml = renderShell(TenantTheme.DEFAULT.copy(loginLayout = LoginLayout.CENTERED))
        val splitHtml = renderShell(TenantTheme.DEFAULT.copy(loginLayout = LoginLayout.SPLIT))

        assertContains(centeredHtml, "data-layout=\"centered\"")
        assertContains(splitHtml, "data-layout=\"split\"")
    }

    @Test
    fun `loginPage rendered with CENTERED and SPLIT does not throw and preserves the card body`() {
        val centeredCtx = viewContext.copy(theme = TenantTheme.DEFAULT.copy(loginLayout = LoginLayout.CENTERED))
        val splitCtx = viewContext.copy(theme = TenantTheme.DEFAULT.copy(loginLayout = LoginLayout.SPLIT))

        val centeredHtml = render(AuthView.loginPage(tenantSlug = "acme", ctx = centeredCtx))
        val splitHtml = render(AuthView.loginPage(tenantSlug = "acme", ctx = splitCtx))

        assertTrue(centeredHtml.contains("card-title"), "CENTERED login page must render the card title")
        assertTrue(splitHtml.contains("card-title"), "SPLIT login page must render the card title")
        assertFalse(centeredHtml.contains("shell--split"))
        assertContains(splitHtml, "shell--split")
    }
}
