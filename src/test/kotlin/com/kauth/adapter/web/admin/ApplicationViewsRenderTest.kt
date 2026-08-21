package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * View-layer tests for the Overview card on [ApplicationViews.applicationDetailPageImpl].
 *
 * Grant types were previously only visible on the Edit form — an operator debugging an
 * `unauthorized_client` error had to leave the detail page to see what was registered.
 */
class ApplicationViewsRenderTest {
    private fun render(page: HTML.() -> Unit): String = createHTML().html { page() }

    private val workspace =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private fun app(grantTypes: Set<GrantType>): Application =
        Application(
            id = ApplicationId(1),
            tenantId = TenantId(1),
            clientId = "billing-worker",
            name = "Billing Worker",
            description = null,
            accessType = AccessType.CONFIDENTIAL,
            enabled = true,
            redirectUris = emptyList(),
            grantTypes = grantTypes,
        )

    @Test
    fun `Overview card lists each registered grant type`() {
        val html =
            render(
                applicationDetailPageImpl(
                    workspace = workspace,
                    application = app(setOf(GrantType.CLIENT_CREDENTIALS)),
                    allWorkspaces = emptyList(),
                    allApps = emptyList(),
                    loggedInAs = "admin",
                ),
            )

        assertTrue(html.contains(EnglishStrings.GRANT_TYPES_LABEL))
        assertTrue(html.contains(GrantType.CLIENT_CREDENTIALS.label))
        assertFalse(html.contains(GrantType.AUTHORIZATION_CODE.label))
    }

    @Test
    fun `Overview card shows None for a bearer-only app with no grants`() {
        val html =
            render(
                applicationDetailPageImpl(
                    workspace = workspace,
                    application = app(emptySet()),
                    allWorkspaces = emptyList(),
                    allApps = emptyList(),
                    loggedInAs = "admin",
                ),
            )

        assertTrue(html.contains(EnglishStrings.GRANT_TYPES_LABEL))
        assertTrue(html.contains(">None<"))
    }
}
