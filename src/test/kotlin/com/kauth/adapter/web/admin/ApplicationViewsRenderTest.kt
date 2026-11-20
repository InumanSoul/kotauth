package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleId
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

    private fun detail(
        grantTypes: Set<GrantType>,
        availableDefaultRoles: List<Role> = emptyList(),
    ): String =
        render(
            applicationDetailPageImpl(
                workspace = workspace,
                application = app(grantTypes),
                allWorkspaces = emptyList(),
                allApps = emptyList(),
                loggedInAs = "admin",
                availableDefaultRoles = availableDefaultRoles,
            ),
        )

    @Test
    fun `a client-credentials-only app hides the sign-in cards and says why`() {
        val html = detail(setOf(GrantType.CLIENT_CREDENTIALS))

        assertFalse(html.contains("Redirect URIs"))
        assertFalse(html.contains("Open Login"))
        assertFalse(html.contains("Registration Defaults"))
        assertFalse(html.contains(">Launcher<"))
        assertTrue(html.contains(EnglishStrings.APP_NO_INTERACTIVE_SIGN_IN))
    }

    @Test
    fun `an authorization-code app keeps the sign-in cards`() {
        val html = detail(setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN))

        assertTrue(html.contains("Redirect URIs"))
        assertTrue(html.contains("Open Login"))
        assertTrue(html.contains("Registration Defaults"))
        assertFalse(html.contains(EnglishStrings.APP_NO_INTERACTIVE_SIGN_IN))
    }

    @Test
    fun `the default-role picker starts on a placeholder, not the first role`() {
        val admin = Role(id = RoleId(7), tenantId = TenantId(1), name = "admin")
        val html = detail(setOf(GrantType.AUTHORIZATION_CODE), availableDefaultRoles = listOf(admin))

        val placeholder = Regex("""<option[^>]*value=""[^>]*>${EnglishStrings.APP_DEFAULT_ROLE_PLACEHOLDER}</option>""")
        assertTrue(placeholder.containsMatchIn(html))
        val selectedOption = Regex("""<option[^>]*selected[^>]*>([^<]*)</option>""").find(html)
        assertTrue(selectedOption?.groupValues?.get(1) == EnglishStrings.APP_DEFAULT_ROLE_PLACEHOLDER)
    }
}
