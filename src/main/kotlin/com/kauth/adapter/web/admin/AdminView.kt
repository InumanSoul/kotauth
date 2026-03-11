package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import kotlinx.html.*

/**
 * View layer for the admin console.
 *
 * Pure functions: data in → HTML out. No HTTP context, no service calls, no side effects.
 * The design tokens are kept in sync with AuthView intentionally — one product, one visual language.
 *
 * Layout strategy:
 *   - loginPage   → same centred card as auth pages (no sidebar — not yet authenticated)
 *   - all others  → AdminShell (sidebar + top bar + main content area)
 */
object AdminView {

    // -------------------------------------------------------------------------
    // Design tokens — must stay in sync with AuthView.Colors
    // -------------------------------------------------------------------------
    private object C {
        const val BG_DEEP    = "#0f0f13"
        const val BG_CARD    = "#1a1a24"
        const val BG_INPUT   = "#252532"
        const val BG_SIDEBAR = "#13131c"
        const val BORDER     = "#2e2e3e"
        const val ACCENT     = "#bb86fc"
        const val ACCENT_DIM = "#9965f4"
        const val TEXT       = "#e8e8f0"
        const val MUTED      = "#6b6b80"
        const val ERROR_BG   = "#2a1a1a"
        const val ERROR_BD   = "#cf6679"
        const val ERROR_TXT  = "#ff8a9b"
        const val SUCCESS_BG = "#1a2a1a"
        const val SUCCESS_BD = "#4caf50"
        const val SUCCESS_TXT= "#81c784"
        const val WARN_BG    = "#2a2214"
        const val WARN_BD    = "#f0a500"
        const val WARN_TXT   = "#ffc947"
    }

    // -------------------------------------------------------------------------
    // Shared CSS
    // -------------------------------------------------------------------------

    private fun baseReset(): String = """
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background: ${C.BG_DEEP};
            color: ${C.TEXT};
            font-family: 'Inter', system-ui, -apple-system, sans-serif;
            font-size: 14px;
        }
        a { color: ${C.ACCENT}; text-decoration: none; }
        a:hover { text-decoration: underline; }
        input, select, textarea {
            width: 100%;
            padding: 0.65rem 0.9rem;
            background: ${C.BG_INPUT};
            border: 1px solid ${C.BORDER};
            border-radius: 6px;
            color: ${C.TEXT};
            font-size: 0.9rem;
            outline: none;
            transition: border-color 0.2s;
        }
        input:focus, select:focus, textarea:focus { border-color: ${C.ACCENT}; }
        input::placeholder, textarea::placeholder { color: ${C.MUTED}; }
        label {
            display: block;
            font-size: 0.75rem;
            font-weight: 500;
            color: ${C.MUTED};
            margin-bottom: 0.35rem;
            text-transform: uppercase;
            letter-spacing: 0.04em;
        }
        .field { margin-bottom: 1rem; }
        .field-hint { font-size: 0.75rem; color: ${C.MUTED}; margin-top: 0.3rem; }
        .btn {
            display: inline-flex;
            align-items: center;
            gap: 0.4rem;
            padding: 0.6rem 1.2rem;
            background: ${C.ACCENT};
            border: none;
            border-radius: 6px;
            color: #0f0f13;
            font-size: 0.85rem;
            font-weight: 700;
            cursor: pointer;
            transition: background 0.2s, transform 0.1s;
            letter-spacing: 0.02em;
        }
        .btn:hover { background: ${C.ACCENT_DIM}; }
        .btn:active { transform: scale(0.98); }
        .btn-ghost {
            background: transparent;
            border: 1px solid ${C.BORDER};
            color: ${C.TEXT};
        }
        .btn-ghost:hover { background: ${C.BG_CARD}; }
        .btn-danger {
            background: ${C.ERROR_BD};
            color: #fff;
        }
        .btn-danger:hover { background: #b54060; }
        .btn-sm { padding: 0.4rem 0.8rem; font-size: 0.78rem; }
        .alert {
            padding: 0.7rem 1rem;
            border-radius: 6px;
            font-size: 0.85rem;
            margin-bottom: 1.25rem;
            border-width: 1px;
            border-style: solid;
        }
        .alert-error   { background: ${C.ERROR_BG};   border-color: ${C.ERROR_BD};   color: ${C.ERROR_TXT}; }
        .alert-success { background: ${C.SUCCESS_BG}; border-color: ${C.SUCCESS_BD}; color: ${C.SUCCESS_TXT}; }
        .alert-warn    { background: ${C.WARN_BG};    border-color: ${C.WARN_BD};    color: ${C.WARN_TXT}; }
        .badge {
            display: inline-block;
            padding: 0.2rem 0.55rem;
            border-radius: 99px;
            font-size: 0.7rem;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.05em;
        }
        .badge-green  { background: #1a3a1a; color: #81c784; border: 1px solid #2d5a2d; }
        .badge-red    { background: #3a1a1a; color: #f48fb1; border: 1px solid #5a2d2d; }
        .badge-purple { background: #2a1a3a; color: ${C.ACCENT}; border: 1px solid #4a2d5a; }
    """.trimIndent()

    private fun shellStyles(): String = """
        .shell { display: flex; min-height: 100vh; }
        .sidebar {
            width: 220px;
            flex-shrink: 0;
            background: ${C.BG_SIDEBAR};
            border-right: 1px solid ${C.BORDER};
            display: flex;
            flex-direction: column;
            padding: 1.5rem 0;
        }
        .sidebar-brand {
            padding: 0 1.25rem 1.5rem;
            border-bottom: 1px solid ${C.BORDER};
            margin-bottom: 1rem;
        }
        .sidebar-brand-name {
            font-size: 1rem;
            font-weight: 700;
            color: ${C.ACCENT};
            letter-spacing: 0.06em;
            text-transform: uppercase;
        }
        .sidebar-brand-sub { font-size: 0.7rem; color: ${C.MUTED}; margin-top: 0.2rem; }
        .sidebar-section {
            padding: 0.25rem 0;
            font-size: 0.68rem;
            font-weight: 600;
            color: ${C.MUTED};
            text-transform: uppercase;
            letter-spacing: 0.08em;
            padding: 0.5rem 1.25rem 0.25rem;
        }
        .nav-item {
            display: flex;
            align-items: center;
            gap: 0.6rem;
            padding: 0.55rem 1.25rem;
            font-size: 0.85rem;
            color: ${C.MUTED};
            cursor: pointer;
            transition: background 0.15s, color 0.15s;
            border-left: 2px solid transparent;
            text-decoration: none;
        }
        .nav-item:hover { background: ${C.BG_CARD}; color: ${C.TEXT}; text-decoration: none; }
        .nav-item.active {
            color: ${C.ACCENT};
            border-left-color: ${C.ACCENT};
            background: rgba(187,134,252,0.07);
        }
        .nav-item-icon { width: 16px; text-align: center; font-size: 0.9rem; }
        .sidebar-spacer { flex: 1; }
        .main {
            flex: 1;
            display: flex;
            flex-direction: column;
            min-width: 0;
        }
        .topbar {
            height: 52px;
            border-bottom: 1px solid ${C.BORDER};
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 0 1.75rem;
            background: ${C.BG_DEEP};
            position: sticky;
            top: 0;
            z-index: 10;
        }
        .topbar-title { font-size: 0.95rem; font-weight: 600; color: ${C.TEXT}; }
        .topbar-right { display: flex; align-items: center; gap: 1rem; }
        .topbar-user { font-size: 0.8rem; color: ${C.MUTED}; }
        .content { padding: 1.75rem; flex: 1; }
        .page-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-bottom: 1.5rem;
        }
        .page-title { font-size: 1.2rem; font-weight: 600; }
        .page-subtitle { font-size: 0.8rem; color: ${C.MUTED}; margin-top: 0.2rem; }
        .card {
            background: ${C.BG_CARD};
            border: 1px solid ${C.BORDER};
            border-radius: 8px;
        }
        .card-body { padding: 1.25rem 1.5rem; }
        .stat-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem; margin-bottom: 1.5rem; }
        .stat-card {
            background: ${C.BG_CARD};
            border: 1px solid ${C.BORDER};
            border-radius: 8px;
            padding: 1.1rem 1.25rem;
        }
        .stat-label { font-size: 0.7rem; color: ${C.MUTED}; text-transform: uppercase; letter-spacing: 0.06em; }
        .stat-value { font-size: 1.6rem; font-weight: 700; color: ${C.ACCENT}; margin-top: 0.25rem; }
        table { width: 100%; border-collapse: collapse; }
        th {
            text-align: left;
            font-size: 0.7rem;
            font-weight: 600;
            color: ${C.MUTED};
            text-transform: uppercase;
            letter-spacing: 0.06em;
            padding: 0.75rem 1rem;
            border-bottom: 1px solid ${C.BORDER};
        }
        td {
            padding: 0.85rem 1rem;
            font-size: 0.85rem;
            border-bottom: 1px solid rgba(46,46,62,0.5);
            color: ${C.TEXT};
            vertical-align: middle;
        }
        tr:last-child td { border-bottom: none; }
        tr:hover td { background: rgba(187,134,252,0.04); }
        .td-muted { color: ${C.MUTED}; font-size: 0.8rem; }
        .td-code {
            font-family: 'JetBrains Mono', 'Fira Code', monospace;
            font-size: 0.8rem;
            color: ${C.ACCENT};
        }
        .empty-state {
            text-align: center;
            padding: 3rem 1rem;
            color: ${C.MUTED};
        }
        .empty-state-icon { font-size: 2.5rem; margin-bottom: 0.75rem; }
        .empty-state-text { font-size: 0.9rem; }
        .form-card {
            background: ${C.BG_CARD};
            border: 1px solid ${C.BORDER};
            border-radius: 8px;
            padding: 1.5rem;
            max-width: 560px;
        }
        .form-section-title {
            font-size: 0.7rem;
            font-weight: 600;
            color: ${C.MUTED};
            text-transform: uppercase;
            letter-spacing: 0.06em;
            margin: 1.25rem 0 0.75rem;
            padding-top: 1.25rem;
            border-top: 1px solid ${C.BORDER};
        }
        .form-section-title:first-of-type { margin-top: 0; padding-top: 0; border-top: none; }
        .checkbox-row {
            display: flex;
            align-items: center;
            gap: 0.6rem;
            margin-bottom: 0.75rem;
        }
        .checkbox-row input[type=checkbox] {
            width: 16px;
            height: 16px;
            accent-color: ${C.ACCENT};
        }
        .checkbox-label { font-size: 0.85rem; color: ${C.TEXT}; cursor: pointer; }
        .breadcrumb {
            display: flex;
            align-items: center;
            gap: 0.4rem;
            font-size: 0.78rem;
            color: ${C.MUTED};
            margin-bottom: 1.25rem;
        }
        .breadcrumb a { color: ${C.MUTED}; }
        .breadcrumb-sep { color: ${C.BORDER}; }
        .breadcrumb-current { color: ${C.TEXT}; }
        .form-actions { display: flex; gap: 0.75rem; margin-top: 1.5rem; }
    """.trimIndent()

    // -------------------------------------------------------------------------
    // Layout primitives
    // -------------------------------------------------------------------------

    /**
     * Renders the full admin shell: sidebar + top bar + [content].
     * [activeNav] highlights the matching sidebar link ("tenants", "users", etc.).
     */
    private fun HTML.adminShell(
        pageTitle: String,
        activeNav: String,
        loggedInAs: String,
        content: DIV.() -> Unit
    ) {
        head {
            title { +"KotAuth Admin — $pageTitle" }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            style { unsafe { +(baseReset() + "\n" + shellStyles()) } }
        }
        body {
            div("shell") {
                // Sidebar
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
                        button(type = ButtonType.submit, classes = "nav-item btn-ghost") {
                            style = "width: 100%; border: none; border-radius: 0; text-align: left; cursor: pointer; background: transparent;"
                            span("nav-item-icon") { +"↩" }
                            +"Sign Out"
                        }
                    }
                }

                // Main area
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

    private fun loginStyles(): String = """
        body {
            display: flex;
            flex-direction: column;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            padding: 1.5rem;
        }
        .brand { margin-bottom: 2rem; text-align: center; }
        .brand-name { font-size: 1.25rem; font-weight: 700; color: ${C.ACCENT}; letter-spacing: 0.05em; text-transform: uppercase; }
        .brand-tagline { font-size: 0.75rem; color: ${C.MUTED}; margin-top: 0.25rem; }
        .login-card {
            background: ${C.BG_CARD};
            border: 1px solid ${C.BORDER};
            border-radius: 12px;
            padding: 2rem;
            width: 100%;
            max-width: 380px;
            box-shadow: 0 20px 60px rgba(0,0,0,0.5);
        }
        .card-title { font-size: 1.2rem; font-weight: 600; margin-bottom: 0.2rem; }
        .card-subtitle { font-size: 0.82rem; color: ${C.MUTED}; margin-bottom: 1.5rem; }
        .btn-full { width: 100%; justify-content: center; padding: 0.8rem; font-size: 0.95rem; margin-top: 0.5rem; }
    """.trimIndent()

    fun loginPage(error: String? = null): HTML.() -> Unit = {
        head {
            title { +"KotAuth | Admin Login" }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            style { unsafe { +(baseReset() + "\n" + loginStyles()) } }
        }
        body {
            div("brand") {
                div("brand-name") { +"KotAuth" }
                div("brand-tagline") { +"Admin Console" }
            }
            div("login-card") {
                h1("card-title") { +"Administrator Login" }
                p("card-subtitle") { +"Access is restricted to master tenant admins." }

                if (error != null) {
                    div("alert alert-error") { +error }
                }

                form(action = "/admin/login", encType = FormEncType.applicationXWwwFormUrlEncoded, method = FormMethod.post) {
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

    // -------------------------------------------------------------------------
    // Dashboard (tenant list)
    // -------------------------------------------------------------------------

    fun dashboardPage(tenants: List<Tenant>, loggedInAs: String): HTML.() -> Unit = {
        adminShell("Dashboard", "dashboard", loggedInAs) {
            div("stat-grid") {
                div("stat-card") {
                    div("stat-label") { +"Total Tenants" }
                    div("stat-value") { +"${tenants.size}" }
                }
                div("stat-card") {
                    div("stat-label") { +"Active Tenants" }
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
                                        if (tenant.registrationEnabled) {
                                            span("badge badge-green") { +"Enabled" }
                                        } else {
                                            span("badge badge-red") { +"Disabled" }
                                        }
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
                form(action = "/admin/tenants", encType = FormEncType.applicationXWwwFormUrlEncoded, method = FormMethod.post) {
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
                        label("checkbox-label") {
                            htmlFor = "registrationEnabled"
                            +"Allow public registration"
                        }
                    }
                    div("checkbox-row") {
                        input(type = InputType.checkBox, name = "emailVerificationRequired") {
                            id = "emailVerificationRequired"
                            if (prefill.emailVerificationRequired) checked = true
                            attributes["value"] = "true"
                        }
                        label("checkbox-label") {
                            htmlFor = "emailVerificationRequired"
                            +"Require email verification"
                        }
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
    // Tenant detail (MVP stub — Phase 2 expands this with tabs)
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
                +"Tenant settings, client management, and user listing are coming in the next phase. For now you can view tenant details and open its login page."
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
                        detailRow("Require Special Char", if (tenant.passwordPolicyRequireSpecial) "Yes" else "No")
                    }
                }
            }
        }
    }

    private fun TBODY.detailRow(label: String, value: String) {
        tr {
            td { style = "color: #6b6b80; width: 200px; font-size: 0.8rem;"; +label }
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
    val emailVerificationRequired: Boolean = false
)
