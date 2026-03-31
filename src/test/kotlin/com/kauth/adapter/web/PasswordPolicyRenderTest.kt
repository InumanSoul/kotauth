package com.kauth.adapter.web

import com.kauth.adapter.web.admin.AdminView
import com.kauth.adapter.web.auth.AuthView
import com.kauth.adapter.web.portal.PortalSession
import com.kauth.adapter.web.portal.PortalView
import com.kauth.domain.model.SecurityConfig
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
 * Pure view-layer tests for password-policy data attributes and placeholder text.
 *
 * View functions are pure: `SecurityConfig` in → HTML string out. No Ktor, no DB.
 * We render with `createHTML()` and assert on the resulting string.
 *
 * Coverage:
 *   - AuthView.registerPage
 *   - AuthView.resetPasswordPage
 *   - PortalView.securityPage
 *   - AdminView.createUserPage
 */
class PasswordPolicyRenderTest {
    // -------------------------------------------------------------------------
    // Rendering helper
    // -------------------------------------------------------------------------

    /**
     * Renders an `HTML.() -> Unit` page function to a plain string.
     *
     * View functions return a lambda; `crossinline` on the `html {}` builder
     * prevents passing them as function references, so we invoke `page()` inside
     * the lambda instead.
     */
    private fun render(page: HTML.() -> Unit): String = createHTML().html { page() }

    // -------------------------------------------------------------------------
    // Shared fixtures
    // -------------------------------------------------------------------------

    private val defaultPolicy = SecurityConfig() // all flags off, minLength = 8

    private val strictPolicy =
        SecurityConfig(
            passwordMinLength = 12,
            passwordRequireUppercase = true,
            passwordRequireNumber = true,
            passwordRequireSpecial = true,
        )

    private val portalSession =
        PortalSession(
            userId = 1,
            tenantId = 1,
            tenantSlug = "acme",
            username = "alice",
        )

    private val baseTenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
        )

    // -------------------------------------------------------------------------
    // AuthView.registerPage
    // -------------------------------------------------------------------------

    @Test
    fun `registerPage renders data-pw-min-length with configured minimum`() {
        val html = render(AuthView.registerPage(tenantSlug = "acme", passwordPolicy = strictPolicy))

        assertTrue(html.contains("""data-pw-min-length="12""""), "Expected data-pw-min-length=\"12\" in rendered HTML")
    }

    @Test
    fun `registerPage renders data-pw-min-length with default minimum when policy is default`() {
        val html = render(AuthView.registerPage(tenantSlug = "acme", passwordPolicy = defaultPolicy))

        assertTrue(html.contains("""data-pw-min-length="8""""), "Expected data-pw-min-length=\"8\" for default policy")
    }

    @Test
    fun `registerPage renders all three require attributes when all policy flags are true`() {
        val html = render(AuthView.registerPage(tenantSlug = "acme", passwordPolicy = strictPolicy))

        assertTrue(html.contains("""data-pw-require-upper="true""""), "Expected data-pw-require-upper attribute")
        assertTrue(html.contains("""data-pw-require-number="true""""), "Expected data-pw-require-number attribute")
        assertTrue(html.contains("""data-pw-require-special="true""""), "Expected data-pw-require-special attribute")
    }

    @Test
    fun `registerPage omits data-pw-require-upper when flag is false`() {
        val html = render(AuthView.registerPage(tenantSlug = "acme", passwordPolicy = defaultPolicy))

        assertFalse(html.contains("data-pw-require-upper"), "data-pw-require-upper must not appear when flag is false")
    }

    @Test
    fun `registerPage omits data-pw-require-number when flag is false`() {
        val html = render(AuthView.registerPage(tenantSlug = "acme", passwordPolicy = defaultPolicy))

        assertFalse(
            html.contains("data-pw-require-number"),
            "data-pw-require-number must not appear when flag is false",
        )
    }

    @Test
    fun `registerPage omits data-pw-require-special when flag is false`() {
        val html = render(AuthView.registerPage(tenantSlug = "acme", passwordPolicy = defaultPolicy))

        assertFalse(
            html.contains("data-pw-require-special"),
            "data-pw-require-special must not appear when flag is false",
        )
    }

    @Test
    fun `registerPage placeholder reflects passwordMinLength`() {
        val html = render(AuthView.registerPage(tenantSlug = "acme", passwordPolicy = strictPolicy))

        assertTrue(html.contains("Minimum 12 characters"), "Placeholder must embed configured minimum length")
    }

    @Test
    fun `registerPage placeholder uses default minimum length when policy is default`() {
        val html = render(AuthView.registerPage(tenantSlug = "acme", passwordPolicy = defaultPolicy))

        assertTrue(html.contains("Minimum 8 characters"), "Placeholder must embed default minimum length")
    }

    // -------------------------------------------------------------------------
    // AuthView.resetPasswordPage
    // -------------------------------------------------------------------------

    @Test
    fun `resetPasswordPage renders data-pw-min-length with configured minimum`() {
        val html =
            render(AuthView.resetPasswordPage(tenantSlug = "acme", token = "tok123", passwordPolicy = strictPolicy))

        assertTrue(
            html.contains("""data-pw-min-length="12""""),
            "Expected data-pw-min-length=\"12\" in reset page HTML",
        )
    }

    @Test
    fun `resetPasswordPage renders all three require attributes when all policy flags are true`() {
        val html =
            render(AuthView.resetPasswordPage(tenantSlug = "acme", token = "tok123", passwordPolicy = strictPolicy))

        assertTrue(html.contains("""data-pw-require-upper="true""""), "Expected data-pw-require-upper on reset page")
        assertTrue(html.contains("""data-pw-require-number="true""""), "Expected data-pw-require-number on reset page")
        assertTrue(
            html.contains("""data-pw-require-special="true""""),
            "Expected data-pw-require-special on reset page",
        )
    }

    @Test
    fun `resetPasswordPage omits optional require attributes when flags are false`() {
        val html =
            render(AuthView.resetPasswordPage(tenantSlug = "acme", token = "tok123", passwordPolicy = defaultPolicy))

        assertFalse(
            html.contains("data-pw-require-upper"),
            "data-pw-require-upper must not appear on reset page when false",
        )
        assertFalse(
            html.contains("data-pw-require-number"),
            "data-pw-require-number must not appear on reset page when false",
        )
        assertFalse(
            html.contains("data-pw-require-special"),
            "data-pw-require-special must not appear on reset page when false",
        )
    }

    @Test
    fun `resetPasswordPage placeholder reflects passwordMinLength`() {
        val html =
            render(AuthView.resetPasswordPage(tenantSlug = "acme", token = "tok123", passwordPolicy = strictPolicy))

        assertTrue(
            html.contains("Minimum 12 characters"),
            "Reset page placeholder must embed configured minimum length",
        )
    }

    // -------------------------------------------------------------------------
    // PortalView.securityPage
    // -------------------------------------------------------------------------

    @Test
    fun `securityPage renders data-pw-min-length with configured minimum`() {
        val html =
            render(
                PortalView.securityPage(
                    slug = "acme",
                    session = portalSession,
                    theme = TenantTheme.DEFAULT,
                    workspaceName = "Acme Corp",
                    sessions = emptyList(),
                    successMsg = null,
                    errorMsg = null,
                    passwordPolicy = strictPolicy,
                ),
            )

        assertTrue(
            html.contains("""data-pw-min-length="12""""),
            "Expected data-pw-min-length=\"12\" in security page HTML",
        )
    }

    @Test
    fun `securityPage renders all three require attributes when all policy flags are true`() {
        val html =
            render(
                PortalView.securityPage(
                    slug = "acme",
                    session = portalSession,
                    theme = TenantTheme.DEFAULT,
                    workspaceName = "Acme Corp",
                    sessions = emptyList(),
                    successMsg = null,
                    errorMsg = null,
                    passwordPolicy = strictPolicy,
                ),
            )

        assertTrue(html.contains("""data-pw-require-upper="true""""), "Expected data-pw-require-upper on security page")
        assertTrue(
            html.contains("""data-pw-require-number="true""""),
            "Expected data-pw-require-number on security page",
        )
        assertTrue(
            html.contains("""data-pw-require-special="true""""),
            "Expected data-pw-require-special on security page",
        )
    }

    @Test
    fun `securityPage omits optional require attributes when flags are false`() {
        val html =
            render(
                PortalView.securityPage(
                    slug = "acme",
                    session = portalSession,
                    theme = TenantTheme.DEFAULT,
                    workspaceName = "Acme Corp",
                    sessions = emptyList(),
                    successMsg = null,
                    errorMsg = null,
                    passwordPolicy = defaultPolicy,
                ),
            )

        assertFalse(
            html.contains("data-pw-require-upper"),
            "data-pw-require-upper must not appear on security page when false",
        )
        assertFalse(
            html.contains("data-pw-require-number"),
            "data-pw-require-number must not appear on security page when false",
        )
        assertFalse(
            html.contains("data-pw-require-special"),
            "data-pw-require-special must not appear on security page when false",
        )
    }

    // -------------------------------------------------------------------------
    // AdminView.createUserPage — reads policy from workspace.securityConfig
    // -------------------------------------------------------------------------

    @Test
    fun `createUserPage renders data-pw-min-length from workspace securityConfig`() {
        val workspace = baseTenant.copy(securityConfig = strictPolicy)

        val html =
            render(
                AdminView.createUserPage(
                    workspace = workspace,
                    allWorkspaces = emptyList(),
                    loggedInAs = "admin",
                ),
            )

        assertTrue(
            html.contains("""data-pw-min-length="12""""),
            "Expected data-pw-min-length=\"12\" on admin create-user page",
        )
    }

    @Test
    fun `createUserPage renders all three require attributes when workspace securityConfig has all flags true`() {
        val workspace = baseTenant.copy(securityConfig = strictPolicy)

        val html =
            render(
                AdminView.createUserPage(
                    workspace = workspace,
                    allWorkspaces = emptyList(),
                    loggedInAs = "admin",
                ),
            )

        assertTrue(
            html.contains("""data-pw-require-upper="true""""),
            "Expected data-pw-require-upper on admin create-user page",
        )
        assertTrue(
            html.contains("""data-pw-require-number="true""""),
            "Expected data-pw-require-number on admin create-user page",
        )
        assertTrue(
            html.contains("""data-pw-require-special="true""""),
            "Expected data-pw-require-special on admin create-user page",
        )
    }

    @Test
    fun `createUserPage omits optional require attributes when workspace securityConfig has flags false`() {
        val workspace = baseTenant.copy(securityConfig = defaultPolicy)

        val html =
            render(
                AdminView.createUserPage(
                    workspace = workspace,
                    allWorkspaces = emptyList(),
                    loggedInAs = "admin",
                ),
            )

        assertFalse(
            html.contains("data-pw-require-upper"),
            "data-pw-require-upper must not appear on admin create-user page when false",
        )
        assertFalse(
            html.contains("data-pw-require-number"),
            "data-pw-require-number must not appear on admin create-user page when false",
        )
        assertFalse(
            html.contains("data-pw-require-special"),
            "data-pw-require-special must not appear on admin create-user page when false",
        )
    }

    @Test
    fun `createUserPage placeholder reflects passwordMinLength from workspace securityConfig`() {
        val workspace = baseTenant.copy(securityConfig = strictPolicy)

        val html =
            render(
                AdminView.createUserPage(
                    workspace = workspace,
                    allWorkspaces = emptyList(),
                    loggedInAs = "admin",
                ),
            )

        assertTrue(
            html.contains("Minimum 12 characters"),
            "Admin create-user placeholder must embed configured minimum length",
        )
    }
}
