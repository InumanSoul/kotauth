package com.kauth.adapter.web

import com.kauth.adapter.web.auth.AuthView
import com.kauth.domain.model.TenantTheme
import com.kauth.infrastructure.EnglishOnlyTranslation
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * Pure view-layer tests for the login page passkey UI additions.
 *
 * Tests: passkey button visibility, autocomplete hint, JS bundle include,
 * passwordLoginDisabled hides the password field and shows the magic-link button.
 */
class LoginPagePasskeyRenderTest {
    private fun render(page: HTML.() -> Unit): String = createHTML().html { page() }

    private val viewContext =
        ViewContext.englishOnly(
            theme = TenantTheme.DEFAULT,
            workspaceName = "Acme Corp",
            translator = EnglishOnlyTranslation(),
        )

    @Test
    fun `login page shows passkey button and autocomplete hint when passkeysEnabled is true`() {
        val html =
            render(
                AuthView.loginPage(
                    tenantSlug = "acme",
                    ctx = viewContext,
                    passkeysEnabled = true,
                ),
            )

        assertContains(html, "Sign in with a passkey")
        assertContains(html, "autocomplete=\"username webauthn\"")
        assertContains(html, "kotauth-passkeys.min.js")
    }

    @Test
    fun `login page hides password field and shows magic-link button when passwordLoginDisabled is true`() {
        val html =
            render(
                AuthView.loginPage(
                    tenantSlug = "acme",
                    ctx = viewContext,
                    passkeysEnabled = true,
                    passwordLoginDisabled = true,
                ),
            )

        assertFalse(
            html.contains("name=\"password\""),
            "password field must not be rendered when passwordLoginDisabled",
        )
        assertContains(html, "Sign in with a magic link")
        assertContains(html, "Sign in with a passkey")
    }

    @Test
    fun `login page omits passkey button when passkeysEnabled is false`() {
        val html =
            render(
                AuthView.loginPage(
                    tenantSlug = "acme",
                    ctx = viewContext,
                    passkeysEnabled = false,
                ),
            )

        assertFalse(
            html.contains("Sign in with a passkey"),
            "passkey button must not appear when passkeysEnabled=false",
        )
    }
}
