package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantTheme
import kotlinx.html.*

/**
 * View layer for the admin console.
 *
 * Pure functions: data in → HTML out. No HTTP context, no service calls, no side effects.
 *
 * Theming strategy — identical to AuthView:
 *   1. TenantTheme.toCssVars() injected as inline :root { } block.
 *   2. kotauth-admin.css linked after — uses var(--token) throughout.
 *   The admin console always uses TenantTheme.DEFAULT (master tenant theme).
 *   The architecture is identical to auth pages so admin theming is a future
 *   one-liner if we ever want it.
 */
object AdminView {

    // -------------------------------------------------------------------------
    // Shared <head> builder
    // -------------------------------------------------------------------------

    private fun HEAD.adminHead(pageTitle: String, theme: TenantTheme = TenantTheme.DEFAULT) {
        title { +"KotAuth Admin — $pageTitle" }
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
        // 1. Theme variables (always DEFAULT for admin console)
        style { unsafe { +theme.toCssVars() } }
        // 2. Admin stylesheet — uses var(--token) throughout
        link(rel = "stylesheet", href = "/static/kotauth-admin.css")
    }

    // -------------------------------------------------------------------------
    // Shell layout
    // -------------------------------------------------------------------------

    private fun HTML.adminShell(
        pageTitle: String,
        activeNav: String,
        loggedInAs: String,
        content: DIV.() -> Unit
    ) {
        head { adminHead(pageTitle) }
        body {
            div("shell") {
                nav("sidebar") {
                    div("sidebar-brand") {
                        div("sidebar-brand-name") { +"KotAuth" }
                        div("sidebar-brand-sub") { +"Admin Console" }
                    }
                    span("sidebar-section") { +"Management" }
                    a("/admin", classes = "nav-item${if (activeNav == "dashboard") " active" else ""}") {
                        span("nav-item-icon") { +"⊞" }
                        +"Dashboard"
                    }
                    a("/admin/tenants", classes = "nav-item${if (activeNav == "tenants") " active" else ""}") {
                        span("nav-item-icon") { +"◫" }
                        +"Tenants"
                    }
                    a("/admin/users", classes = "nav-item${if (activeNav == "users") " active" else ""}") {
                        span("nav-item-icon") { +"⊙" }
                        +"Users"
                    }
                    a("/admin/clients", classes = "nav-item${if (activeNav == "clients") " active" else ""}") {
                        span("nav-item-icon") { +"◈" }
                        +"Clients"
                    }
                    span("sidebar-section") { +"System" }
                    a("/admin/settings", classes = "nav-item${if (activeNav == "settings") " active" else ""}") {
                        span("nav-item-icon") { +"⊕" }
                        +"Settings"
                    }
                    div("sidebar-spacer") {}
                    form(action = "/admin/logout", method = FormMethod.post) {
                        button(type = ButtonType.submit) {
                            style = "width:100%; display:flex; align-items:center; gap:0.6rem; padding:0.55rem 1.25rem; background:transparent; border:none; color:var(--muted); font-size:0.85rem; cursor:pointer; font-family:inherit;"
                            span("nav-item-icon") { +"↩" }
                            +"Sign Out"
                        }
                    }
                }

                div("main") {
                    div("topbar") {
                        span("topbar-title") { +pageTitle }
                        div("topbar-right") {
                            span("topbar-user") { +"Signed in as $loggedInAs" }
                        }
                    }
                    div("content") {
                        content()
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Login page
    // -------------------------------------------------------------------------

    fun loginPage(error: String? = null): HTML.() -> Unit = {
        head { adminHead("Login") }
        body {
            div("login-shell") {
                div("brand") {
                    div("brand-name") { +"KotAuth" }
                    div("brand-sub") { +"Admin Console" }
                }
                div("login-card") {
                    h1("card-title") { +"Administrator Login" }
                    p("card-subtitle") { +"Access is restricted to master tenant admins." }

                    if (error != null) {
                        div("alert alert-error") { +error }
                    }

                    form(
                        action = "/admin/login",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post
                    ) {
                        div("field") {
                            label { htmlFor = "username"; +"Username" }
                            input(type = InputType.text, name = "username") {
                                id = "username"
                                placeholder = "admin"
                                attributes["autocomplete"] = "username"
                                required = true
                                attributes["autofocus"] = "true"
                            }
                        }
                        div("field") {
                            label { htmlFor = "password"; +"Password" }
                            input(type = InputType.password, name = "password") {
                                id = "password"
                                placeholder = "Enter password"
                                attributes["autocomplete"] = "current-password"
                                required = true
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn btn-full") { +"Sign In" }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dashboard
    // -------------------------------------------------------------------------

    fun dashboardPage(tenants: List<Tenant>, loggedInAs: String): HTML.() -> Unit = {
        adminShell("Dashboard", "dashboard", loggedInAs) {
            div("stat-grid") {
                div("stat-card") {
                    div("stat-label") { +"Total Tenants" }
                    div("stat-value") { +"${tenants.size}" }
                }
                div("stat-card") {
                    div("stat-label") { +"Registration Enabled" }
                    div("stat-value") { +"${tenants.count { it.registrationEnabled }}" }
                }
                div("stat-card") {
                    div("stat-label") { +"Master Tenant" }
                    div("stat-value") { +"1" }
                }
            }

            div("page-header") {
                div {
                    p("page-title") { +"Tenants" }
                    p("page-subtitle") { +"All authorization boundary tenants" }
                }
                a("/admin/tenants/new", classes = "btn") { +"+ New Tenant" }
            }

            div("card") {
                if (tenants.isEmpty()) {
                    div("empty-state") {
                        div("empty-state-icon") { +"◫" }
                        p("empty-state-text") { +"No tenants yet. Create your first one." }
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Slug" }
                                th { +"Display Name" }
                                th { +"Registration" }
                                th { +"Token TTL" }
                                th { +"Actions" }
                            }
                        }
                        tbody {
                            tenants.forEach { tenant ->
                                tr {
                                    td { span("td-code") { +tenant.slug } }
                                    td { +tenant.displayName }
                                    td {
                                        if (tenant.registrationEnabled)
                                            span("badge badge-green") { +"Enabled" }
                                        else
                                            span("badge badge-red") { +"Disabled" }
                                    }
                                    td { span("td-muted") { +"${tenant.tokenExpirySeconds}s" } }
                                    td {
                                        a("/admin/tenants/${tenant.slug}", classes = "btn btn-ghost btn-sm") { +"View" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Create tenant form
    // -------------------------------------------------------------------------

    fun createTenantPage(
        loggedInAs: String,
        error: String? = null,
        prefill: TenantPrefill = TenantPrefill()
    ): HTML.() -> Unit = {
        adminShell("New Tenant", "tenants", loggedInAs) {
            div("breadcrumb") {
                a("/admin") { +"Dashboard" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"New Tenant" }
            }

            div("page-header") {
                div {
                    p("page-title") { +"Create Tenant" }
                    p("page-subtitle") { +"A tenant is an isolated authorization boundary — like a Keycloak realm." }
                }
            }

            if (error != null) {
                div("alert alert-error") { +error }
            }

            div("form-card") {
                form(
                    action = "/admin/tenants",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post
                ) {
                    p("form-section-title") { +"Identity" }

                    div("field") {
                        label { htmlFor = "slug"; +"Slug" }
                        input(type = InputType.text, name = "slug") {
                            id = "slug"
                            placeholder = "my-app"
                            value = prefill.slug
                            required = true
                            attributes["pattern"] = "[a-z0-9-]+"
                        }
                        p("field-hint") { +"Lowercase letters, numbers, and hyphens only. Used in URLs: /t/my-app/login" }
                    }
                    div("field") {
                        label { htmlFor = "displayName"; +"Display Name" }
                        input(type = InputType.text, name = "displayName") {
                            id = "displayName"
                            placeholder = "My Application"
                            value = prefill.displayName
                            required = true
                        }
                    }
                    div("field") {
                        label { htmlFor = "issuerUrl"; +"Issuer URL (optional)" }
                        input(type = InputType.url, name = "issuerUrl") {
                            id = "issuerUrl"
                            placeholder = "https://auth.myapp.com"
                            value = prefill.issuerUrl
                        }
                        p("field-hint") { +"Used as the 'iss' claim in tokens. Defaults to /t/{slug} if left blank." }
                    }

                    p("form-section-title") { +"Registration Policy" }

                    div("checkbox-row") {
                        input(type = InputType.checkBox, name = "registrationEnabled") {
                            id = "registrationEnabled"
                            if (prefill.registrationEnabled) checked = true
                            attributes["value"] = "true"
                        }
                        label("checkbox-label") { htmlFor = "registrationEnabled"; +"Allow public registration" }
                    }
                    div("checkbox-row") {
                        input(type = InputType.checkBox, name = "emailVerificationRequired") {
                            id = "emailVerificationRequired"
                            if (prefill.emailVerificationRequired) checked = true
                            attributes["value"] = "true"
                        }
                        label("checkbox-label") { htmlFor = "emailVerificationRequired"; +"Require email verification" }
                    }

                    p("form-section-title") { +"Branding" }

                    div("field") {
                        label { htmlFor = "themeAccentColor"; +"Accent Color" }
                        input(type = InputType.color, name = "themeAccentColor") {
                            id = "themeAccentColor"
                            value = prefill.themeAccentColor
                        }
                        p("field-hint") { +"Primary brand color — buttons, links, focus rings." }
                    }
                    div("field") {
                        label { htmlFor = "themeLogoUrl"; +"Logo URL (optional)" }
                        input(type = InputType.url, name = "themeLogoUrl") {
                            id = "themeLogoUrl"
                            placeholder = "https://cdn.myapp.com/logo.png"
                            value = prefill.themeLogoUrl
                        }
                        p("field-hint") { +"Displayed above the login card. Max 180×48px recommended." }
                    }

                    div("form-actions") {
                        button(type = ButtonType.submit, classes = "btn") { +"Create Tenant" }
                        a("/admin", classes = "btn btn-ghost") { +"Cancel" }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tenant detail
    // -------------------------------------------------------------------------

    fun tenantDetailPage(tenant: Tenant, loggedInAs: String): HTML.() -> Unit = {
        adminShell("Tenant: ${tenant.displayName}", "tenants", loggedInAs) {
            div("breadcrumb") {
                a("/admin") { +"Dashboard" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +tenant.slug }
            }

            div("page-header") {
                div {
                    p("page-title") { +tenant.displayName }
                    span("td-code") { +tenant.slug }
                    if (tenant.isMaster) {
                        span("badge badge-purple") {
                            style = "margin-left: 0.6rem; vertical-align: middle;"
                            +"Master"
                        }
                    }
                }
                a("/t/${tenant.slug}/login", classes = "btn btn-ghost btn-sm") {
                    attributes["target"] = "_blank"
                    +"Open Login Page ↗"
                }
            }

            div("alert alert-warn") {
                +"Tenant settings editing, client management, and user listing are coming in the next phase."
            }

            div("card card-body") {
                style = "max-width: 480px;"
                table {
                    style = "width: 100%;"
                    tbody {
                        detailRow("Slug", tenant.slug)
                        detailRow("Display Name", tenant.displayName)
                        detailRow("Issuer URL", tenant.issuerUrl ?: "— (auto)")
                        detailRow("Token TTL", "${tenant.tokenExpirySeconds}s")
                        detailRow("Refresh Token TTL", "${tenant.refreshTokenExpirySeconds}s")
                        detailRow("Registration", if (tenant.registrationEnabled) "Enabled" else "Disabled")
                        detailRow("Email Verification", if (tenant.emailVerificationRequired) "Required" else "Not required")
                        detailRow("Min Password Length", "${tenant.passwordPolicyMinLength}")
                        detailRow("Accent Color", tenant.theme.accentColor)
                        detailRow("Logo URL", tenant.theme.logoUrl ?: "— (default)")
                    }
                }
            }
        }
    }

    private fun TBODY.detailRow(label: String, value: String) {
        tr {
            td { style = "color:var(--muted); width:200px; font-size:0.8rem;"; +label }
            td { +value }
        }
    }
}

/**
 * Holds create-tenant form values for prefill after a failed submission.
 */
data class TenantPrefill(
    val slug: String = "",
    val displayName: String = "",
    val issuerUrl: String = "",
    val registrationEnabled: Boolean = true,
    val emailVerificationRequired: Boolean = false,
    val themeAccentColor: String = "#bb86fc",
    val themeLogoUrl: String = ""
)
