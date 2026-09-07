package com.kauth.adapter.web.admin

import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The workspace overview's Identity Providers tile.
 *
 * It previously announced "None / Password auth only" as literal text, reading no data,
 * so it contradicted the Identity Providers page for any workspace that had one. These
 * assert the tile against each state it can actually be in.
 */
class WorkspaceOverviewInsightsTest {
    private fun render(page: HTML.() -> Unit): String = createHTML().html { page() }

    private val workspace =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
        )

    private fun provider(
        key: String,
        enabled: Boolean,
        displayName: String? = null,
    ) = IdentityProvider(
        tenantId = workspace.id,
        provider = ProviderKey.of(key)!!,
        clientId = "client-$key",
        clientSecret = "secret",
        enabled = enabled,
        kind = ProviderKind.OIDC,
        displayName = displayName,
    )

    private fun overview(providers: List<IdentityProvider>): String =
        render(
            AdminView.workspaceDetailPage(
                workspace = workspace,
                allWorkspaces = emptyList(),
                loggedInAs = "admin",
                identityProviders = providers,
            ),
        )

    @Test
    fun `reports password-only when no provider is configured`() {
        val html = overview(emptyList())

        assertContains(html, "Password auth only")
        assertFalse(html.contains("enabled</span>"), "No count should be shown when there is nothing to count")
    }

    @Test
    fun `counts the enabled providers and names them`() {
        val html =
            overview(
                listOf(
                    provider("workforce-sso", enabled = true, displayName = "Workforce SSO"),
                    provider("partner-idp", enabled = true, displayName = "Partner IdP"),
                    provider("legacy-idp", enabled = false, displayName = "Legacy IdP"),
                ),
            )

        assertContains(html, "2 enabled")
        assertContains(html, "Workforce SSO")
        assertContains(html, "Partner IdP")
        assertFalse(html.contains("Password auth only"), "Password-only is false once a provider is enabled")
        assertFalse(html.contains("Legacy IdP"), "A disabled provider is not one users can sign in through")
    }

    @Test
    fun `distinguishes configured-but-all-disabled from nothing configured`() {
        val html = overview(listOf(provider("workforce-sso", enabled = false)))

        assertContains(html, "1 configured")
        assertContains(html, "None enabled")
        assertTrue(
            html.contains("insight-item__value--warn"),
            "A provider configured but switched off is a warning state, not a neutral one",
        )
    }

    @Test
    fun `falls back to the provider key when no display name is set`() {
        val html = overview(listOf(provider("workforce-sso", enabled = true)))

        assertContains(html, "workforce-sso")
    }
}
