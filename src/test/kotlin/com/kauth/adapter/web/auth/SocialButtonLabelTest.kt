package com.kauth.adapter.web.auth

import com.kauth.adapter.web.ViewContext
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.infrastructure.EnglishOnlyTranslation
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure view-layer tests for the sign-in button's label.
 *
 * `IDP_DISPLAY_NAME_HINT` promises the display name is "shown on the sign-in button". Every route
 * used to narrow the row to its [ProviderKey] before the view saw it, so the button title-cased
 * the key instead and the promise was never kept.
 */
class SocialButtonLabelTest {
    private fun render(page: HTML.() -> Unit): String = createHTML().html { page() }

    private val ctx =
        ViewContext(
            theme = TenantTheme.DEFAULT,
            workspaceName = "Acme",
            locale = "en",
            translator = EnglishOnlyTranslation(),
        )

    private val acmeKey = requireNotNull(ProviderKey.of("acme-sso"))

    private fun row(
        key: ProviderKey,
        displayName: String?,
    ) = IdentityProvider(
        tenantId = TenantId(1),
        provider = key,
        clientId = "client",
        clientSecret = "secret",
        kind = if (key in ProviderKey.RESERVED) ProviderKind.OAUTH2 else ProviderKind.OIDC,
        displayName = displayName,
    )

    @Test
    fun `a brokered provider is labelled with the display name the operator chose`() {
        val html =
            render(
                AuthView.loginPage(
                    tenantSlug = "acme",
                    ctx = ctx,
                    enabledProviders = listOf(row(acmeKey, "ACME Single Sign-On")).asLoginProviders(),
                ),
            )

        assertTrue(html.contains("Continue with ACME Single Sign-On"), "Expected the operator's label in: $html")
        assertFalse(html.contains("Acme Sso"), "The title-cased key must not survive alongside a display name")
    }

    @Test
    fun `a brokered provider with no display name still falls back to its key`() {
        val html =
            render(
                AuthView.loginPage(
                    tenantSlug = "acme",
                    ctx = ctx,
                    enabledProviders = listOf(row(acmeKey, null)).asLoginProviders(),
                ),
            )

        assertTrue(html.contains("Continue with Acme Sso"), "Expected the title-cased key fallback in: $html")
    }

    @Test
    fun `a blank display name is treated as none rather than rendered`() {
        val html =
            render(
                AuthView.loginPage(
                    tenantSlug = "acme",
                    ctx = ctx,
                    enabledProviders = listOf(row(acmeKey, "   ")).asLoginProviders(),
                ),
            )

        assertTrue(html.contains("Continue with Acme Sso"), "Expected the fallback for a blank name in: $html")
    }

    @Test
    fun `the button still links by provider key, not by display name`() {
        val html =
            render(
                AuthView.loginPage(
                    tenantSlug = "acme",
                    ctx = ctx,
                    enabledProviders = listOf(row(acmeKey, "ACME Single Sign-On")).asLoginProviders(),
                ),
            )

        // The label is decoration; the key is the route. Carrying the row must not change the URL.
        assertTrue(html.contains("/t/acme/auth/social/acme-sso/redirect"), "Expected the key in the href: $html")
    }

    @Test
    fun `a built-in provider keeps its brand label when no display name is set`() {
        val html =
            render(
                AuthView.loginPage(
                    tenantSlug = "acme",
                    ctx = ctx,
                    enabledProviders = listOf(row(ProviderKey.GOOGLE, null)).asLoginProviders(),
                ),
            )

        assertTrue(html.contains("Continue with Google"), "Expected the branded label in: $html")
    }

    @Test
    fun `the registration page carries the display name too`() {
        val html =
            render(
                AuthView.registerPage(
                    tenantSlug = "acme",
                    ctx = ctx,
                    enabledProviders = listOf(row(acmeKey, "ACME Single Sign-On")).asLoginProviders(),
                ),
            )

        assertTrue(html.contains("Continue with ACME Single Sign-On"), "Expected the operator's label in: $html")
    }

    @Test
    fun `the mapper keeps the key and the display name of every row`() {
        val mapped = listOf(row(acmeKey, "ACME Single Sign-On"), row(ProviderKey.GOOGLE, null)).asLoginProviders()

        assertEquals(listOf(acmeKey, ProviderKey.GOOGLE), mapped.map { it.key })
        assertEquals(listOf("ACME Single Sign-On", null), mapped.map { it.displayName })
    }
}
