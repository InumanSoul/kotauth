package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantTheme
import kotlinx.html.*

/**
 * View layer for the admin console.
 *
 * Pure functions: data in → HTML out. No HTTP context, no service calls, no side effects.
 *
 * Shell architecture (matches design/kotauth.pen "Admin Side Nav"):
 *
 *   .shell
 *   ├── .shell-topbar
 *   │     ├── .ws-dropdown (<details>)  — workspace switcher with live dropdown
 *   │     ├── .topbar-search-wrap       — search (center)
 *   │     └── .topbar-right             — new workspace btn + profile avatar
 *   └── .shell-body
 *       ├── .rail      — 88px icon nav (Apps / Directory / Security / Logs / Settings)
 *       ├── .ctx-panel — 220px workspace-scoped context nav (apps list + app sections)
 *       └── .main      — page content
 *
 * Terminology (public-facing):
 *   Workspace   = internal Tenant  (what an org owns)
 *   Application = internal Client  (what authenticates against a workspace)
 */
object AdminView {

    // -------------------------------------------------------------------------
    // Shared <head> builder
    // -------------------------------------------------------------------------

    private fun HEAD.adminHead(pageTitle: String, theme: TenantTheme = TenantTheme.DEFAULT) {
        title { +"KotAuth — $pageTitle" }
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
        style { unsafe { +theme.toCssVars() } }
        link(rel = "stylesheet", href = "/static/kotauth-admin.css")
        style {
            unsafe {
                +"""
                @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Inconsolata:wght@400;500;700&display=swap');
                """.trimIndent()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shell layout
    //
    // Parameters:
    //   activeRail       — "apps" | "directory" | "security" | "logs" | "settings"
    //   allWorkspaces    — (slug, displayName) list for the switcher dropdown
    //   workspaceName    — display name of the CURRENT workspace (shown in switcher)
    //   workspaceSlug    — current workspace slug; null = no workspace selected (dashboard)
    //   apps             — (slug, name) pairs for the ctx panel application list
    //   activeAppSlug    — highlighted app in the ctx panel
    //   activeAppSection — highlighted sub-section in the ctx panel
    //   loggedInAs       — username for the profile avatar
    // -------------------------------------------------------------------------

    private fun HTML.adminShell(
        pageTitle: String,
        activeRail: String = "apps",
        allWorkspaces: List<Pair<String, String>> = emptyList(),
        workspaceName: String = "KotAuth",
        workspaceSlug: String? = null,
        apps: List<Pair<String, String>> = emptyList(),
        activeAppSlug: String? = null,
        activeAppSection: String = "overview",
        loggedInAs: String,
        content: DIV.() -> Unit
    ) {
        head { adminHead(pageTitle) }
        body {
            div("shell") {

                // ----- Top bar ------------------------------------------------
                div("shell-topbar") {

                    // Left: workspace switcher dropdown (<details> — zero JS)
                    details("ws-dropdown") {
                        // Summary = the always-visible trigger
                        summary("ws-switcher") {
                            div("ws-badge") {
                                +(workspaceName.firstOrNull()?.uppercaseChar()?.toString() ?: "K")
                            }
                            div("ws-meta") {
                                span("ws-name") { +workspaceName }
                                span("ws-label") { +"workspace" }
                            }
                            span("ws-chevron-icon") {}
                        }

                        // Dropdown menu
                        div("ws-dropdown-menu") {
                            if (allWorkspaces.isEmpty()) {
                                span("ws-dropdown-empty") { +"No workspaces yet" }
                            } else {
                                allWorkspaces.forEach { (slug, name) ->
                                    val isActive = slug == workspaceSlug
                                    a(
                                        href = "/admin/workspaces/$slug",
                                        classes = "ws-dropdown-item${if (isActive) " active" else ""}"
                                    ) {
                                        div("ws-dropdown-item-badge") {
                                            +(name.firstOrNull()?.uppercaseChar()?.toString() ?: slug.first().uppercaseChar().toString())
                                        }
                                        span("ws-dropdown-item-name") { +name }
                                        if (isActive) span("ws-dropdown-item-check") { +"✓" }
                                    }
                                }
                            }
                        }
                    }

                    // Center: search
                    div("topbar-search-wrap") {
                        input(type = InputType.search, classes = "topbar-search") {
                            placeholder = "Search apps, users, roles…"
                        }
                    }

                    // Right: new workspace + profile avatar
                    div("topbar-right") {
                        a("/admin/workspaces/new", classes = "btn-new-ws") {
                            span("btn-new-ws-icon") { +"+" }
                            span("btn-new-ws-label") { +"New Workspace" }
                        }
                        div("topbar-avatar") {
                            attributes["title"] = "Signed in as $loggedInAs"
                            +(loggedInAs.firstOrNull()?.uppercaseChar()?.toString() ?: "A")
                        }
                    }
                }

                // ----- Body: rail + ctx-panel + main --------------------------
                div("shell-body") {

                    // Rail (88px icon nav)
                    div("rail") {
                        a("/admin", classes = "rail-brand") { +"K" }
                        div("rail-nav") {
                            railItem("/admin",            "apps",      activeRail, "apps")
                            railItem("/admin/directory",  "directory", activeRail, "directory")
                            railItem("/admin/security",   "security",  activeRail, "security")
                            railItem("/admin/logs",       "logs",      activeRail, "logs")
                        }
                        div("rail-spacer") {}
                        div("rail-nav") {
                            railItem("/admin/settings", "settings", activeRail, "settings")
                        }
                    }

                    // Context panel (220px)
                    div("ctx-panel") {
                        div("ctx-nav") {
                            when (activeRail) {
                                "apps" -> renderAppsCtxPanel(workspaceSlug, apps, activeAppSlug, activeAppSection)
                                "directory" -> renderDirectoryCtxPanel(workspaceSlug, activeAppSection)
                                "security"  -> renderSecurityCtxPanel(workspaceSlug, activeAppSection)
                                "logs"      -> renderLogsCtxPanel(activeAppSection)
                                "settings"  -> renderSettingsCtxPanel(activeAppSection)
                            }
                        }
                    }

                    // Main content
                    div("main") {
                        div("content") {
                            content()
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Context panel sections
    // -------------------------------------------------------------------------

    private fun DIV.renderAppsCtxPanel(
        workspaceSlug: String?,
        apps: List<Pair<String, String>>,
        activeAppSlug: String?,
        activeAppSection: String
    ) {
        if (workspaceSlug == null) {
            // No workspace selected — guide the user
            div("ctx-empty") {
                div("ctx-empty-icon") { +"◫" }
                p("ctx-empty-text") { +"Select a workspace to view its applications." }
            }
            return
        }

        span("ctx-section-title") { +"Applications" }

        if (apps.isEmpty()) {
            // Workspace selected but has no apps yet
            div("ctx-empty") {
                div("ctx-empty-icon") { +"⊡" }
                p("ctx-empty-text") { +"No applications yet." }
                a("/admin/workspaces/$workspaceSlug/applications/new", classes = "ctx-empty-action") {
                    +"+ Create one"
                }
            }
        } else {
            apps.forEach { (appSlug, appName) ->
                a(
                    href = "/admin/workspaces/$workspaceSlug/applications/$appSlug",
                    classes = "ctx-item${if (appSlug == activeAppSlug) " active" else ""}"
                ) {
                    span("ctx-item-label") { +appName }
                }
            }
        }

        // Sub-sections — only when an app is drilled into
        if (activeAppSlug != null) {
            val base = "/admin/workspaces/$workspaceSlug/applications/$activeAppSlug"
            span("ctx-section-title") { +"App sections" }
            ctxLink("$base/overview",       "overview",       activeAppSection, "Overview")
            ctxLink("$base/authentication", "authentication", activeAppSection, "Authentication")
            ctxLink("$base/users",          "users",          activeAppSection, "Users")
            ctxLink("$base/roles",          "roles",          activeAppSection, "Roles & permissions")
            ctxLink("$base/tokens",         "tokens",         activeAppSection, "Tokens & sessions")
            ctxLink("$base/webhooks",       "webhooks",       activeAppSection, "Webhooks")
        }
    }

    private fun DIV.renderDirectoryCtxPanel(workspaceSlug: String?, activeSection: String) {
        span("ctx-section-title") { +"Directory" }
        val base = if (workspaceSlug != null) "/admin/workspaces/$workspaceSlug" else "/admin"
        ctxLink("$base/users",  "users",  activeSection, "Users")
        ctxLink("$base/groups", "groups", activeSection, "Groups")
        ctxLink("$base/roles",  "roles",  activeSection, "Roles")
    }

    private fun DIV.renderSecurityCtxPanel(workspaceSlug: String?, activeSection: String) {
        span("ctx-section-title") { +"Security" }
        val base = if (workspaceSlug != null) "/admin/workspaces/$workspaceSlug" else "/admin"
        ctxLink("$base/mfa",      "mfa",      activeSection, "MFA")
        ctxLink("$base/sessions", "sessions", activeSection, "Sessions")
        ctxLink("$base/audit",    "audit",    activeSection, "Audit log")
    }

    private fun DIV.renderLogsCtxPanel(activeSection: String) {
        span("ctx-section-title") { +"Logs" }
        ctxLink("/admin/logs/events", "events", activeSection, "Events")
        ctxLink("/admin/logs/errors", "errors", activeSection, "Errors")
    }

    private fun DIV.renderSettingsCtxPanel(activeSection: String) {
        span("ctx-section-title") { +"Settings" }
        ctxLink("/admin/settings/general",  "general",  activeSection, "General")
        ctxLink("/admin/settings/smtp",     "smtp",     activeSection, "SMTP")
        ctxLink("/admin/settings/security", "security", activeSection, "Security policy")
    }

    // -------------------------------------------------------------------------
    // Rail item helper — SVG icon via inline data attribute
    // -------------------------------------------------------------------------

    private fun DIV.railItem(href: String, key: String, activeKey: String, iconKey: String) {
        val (icon, label) = when (iconKey) {
            "apps"      -> Pair(
                """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>""",
                "Apps"
            )
            "directory" -> Pair(
                """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>""",
                "Directory"
            )
            "security"  -> Pair(
                """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><polyline points="9 12 11 14 15 10"/></svg>""",
                "Security"
            )
            "logs"      -> Pair(
                """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>""",
                "Logs"
            )
            "settings"  -> Pair(
                """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>""",
                "Settings"
            )
            else -> Pair("", iconKey)
        }

        a(href, classes = "rail-item${if (key == activeKey) " active" else ""}") {
            span("rail-icon") { unsafe { +icon } }
            span("rail-item-label") { +label }
        }
    }

    // Context panel nav link helper
    private fun DIV.ctxLink(href: String, key: String, activeKey: String, label: String) {
        a(href, classes = "ctx-item${if (key == activeKey) " active" else ""}") {
            span("ctx-item-label") { +label }
            if (key == activeKey) span("ctx-item-dot") {}
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
                    div("brand-mark") { +"K" }
                    div("brand-name") { +"KotAuth" }
                    div("brand-sub") { +"Admin Console" }
                }
                div("login-card") {
                    h1("card-title") { +"Administrator Login" }
                    p("card-subtitle") { +"Access is restricted to master workspace admins." }
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
                                id = "username"; placeholder = "admin"
                                attributes["autocomplete"] = "username"
                                required = true; attributes["autofocus"] = "true"
                            }
                        }
                        div("field") {
                            label { htmlFor = "password"; +"Password" }
                            input(type = InputType.password, name = "password") {
                                id = "password"; placeholder = "Enter password"
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
    // Dashboard — workspace list overview
    // -------------------------------------------------------------------------

    fun dashboardPage(
        workspaces: List<Tenant>,
        loggedInAs: String
    ): HTML.() -> Unit = {
        val wsPairs = workspaces.map { it.slug to it.displayName }
        adminShell(
            pageTitle = "Workspaces",
            activeRail = "apps",
            allWorkspaces = wsPairs,
            workspaceName = "KotAuth",
            workspaceSlug = null,   // no workspace selected — ctx panel shows empty state
            loggedInAs = loggedInAs
        ) {
            div("stat-grid") {
                div("stat-card") {
                    div("stat-label") { +"Total Workspaces" }
                    div("stat-value") { +"${workspaces.size}" }
                }
                div("stat-card") {
                    div("stat-label") { +"Registration Enabled" }
                    div("stat-value") { +"${workspaces.count { it.registrationEnabled }}" }
                }
                div("stat-card") {
                    div("stat-label") { +"Master Workspace" }
                    div("stat-value") { +"1" }
                }
            }

            div("page-header") {
                div {
                    p("page-title") { +"Workspaces" }
                    p("page-subtitle") { +"All authorization boundary workspaces" }
                }
            }

            div("card") {
                if (workspaces.isEmpty()) {
                    div("empty-state") {
                        div("empty-state-icon") { +"◫" }
                        p("empty-state-text") { +"No workspaces yet." }
                        p("empty-state-text") {
                            style = "margin-top:0.5rem; font-size:0.8rem;"
                            +"Use the "
                            strong { +"New Workspace" }
                            +" button in the top bar to create your first one."
                        }
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Slug" }; th { +"Name" }
                                th { +"Registration" }; th { +"Token TTL" }
                                th { +"" }
                            }
                        }
                        tbody {
                            workspaces.forEach { ws ->
                                tr {
                                    td { span("td-code") { +ws.slug } }
                                    td { +ws.displayName }
                                    td {
                                        if (ws.registrationEnabled) span("badge badge-green") { +"Enabled" }
                                        else span("badge badge-red") { +"Disabled" }
                                    }
                                    td { span("td-muted") { +"${ws.tokenExpirySeconds}s" } }
                                    td {
                                        a("/admin/workspaces/${ws.slug}", classes = "btn btn-ghost btn-sm") {
                                            +"Open →"
                                        }
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
    // Create workspace form
    // -------------------------------------------------------------------------

    fun createWorkspacePage(
        loggedInAs: String,
        allWorkspaces: List<Pair<String, String>> = emptyList(),
        error: String? = null,
        prefill: WorkspacePrefill = WorkspacePrefill()
    ): HTML.() -> Unit = {
        adminShell(
            pageTitle = "New Workspace",
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            workspaceName = "KotAuth",
            workspaceSlug = null,
            loggedInAs = loggedInAs
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"New Workspace" }
            }
            div("page-header") {
                div {
                    p("page-title") { +"Create Workspace" }
                    p("page-subtitle") {
                        +"A workspace is an isolated authorization boundary — like an Auth0 tenant."
                    }
                }
            }
            if (error != null) {
                div("alert alert-error") { +error }
            }
            div("form-card") {
                form(
                    action = "/admin/workspaces",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post
                ) {
                    p("form-section-title") { +"Identity" }
                    div("field") {
                        label { htmlFor = "slug"; +"Slug" }
                        input(type = InputType.text, name = "slug") {
                            id = "slug"; placeholder = "my-company"; value = prefill.slug
                            required = true; attributes["pattern"] = "[a-z0-9-]+"
                        }
                        p("field-hint") { +"Lowercase letters, numbers, hyphens. Used in token URLs: /t/my-company/…" }
                    }
                    div("field") {
                        label { htmlFor = "displayName"; +"Display Name" }
                        input(type = InputType.text, name = "displayName") {
                            id = "displayName"; placeholder = "Acme Inc"; value = prefill.displayName
                            required = true
                        }
                    }
                    div("field") {
                        label { htmlFor = "issuerUrl"; +"Issuer URL (optional)" }
                        input(type = InputType.url, name = "issuerUrl") {
                            id = "issuerUrl"; placeholder = "https://auth.acme.com"; value = prefill.issuerUrl
                        }
                        p("field-hint") { +"The 'iss' claim in tokens. Defaults to /t/{slug} if blank." }
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
                        label("checkbox-label") {
                            htmlFor = "emailVerificationRequired"; +"Require email verification"
                        }
                    }
                    p("form-section-title") { +"Branding" }
                    div("field") {
                        label { htmlFor = "themeAccentColor"; +"Accent Color" }
                        input(type = InputType.color, name = "themeAccentColor") {
                            id = "themeAccentColor"; value = prefill.themeAccentColor
                        }
                        p("field-hint") { +"Primary brand color used on the tenant's login page." }
                    }
                    div("field") {
                        label { htmlFor = "themeLogoUrl"; +"Logo URL (optional)" }
                        input(type = InputType.url, name = "themeLogoUrl") {
                            id = "themeLogoUrl"
                            placeholder = "https://cdn.acme.com/logo.png"; value = prefill.themeLogoUrl
                        }
                        p("field-hint") { +"Shown above the login card. Max 180×48px recommended." }
                    }
                    div("form-actions") {
                        button(type = ButtonType.submit, classes = "btn") { +"Create Workspace" }
                        a("/admin", classes = "btn btn-ghost") { +"Cancel" }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Workspace detail
    // -------------------------------------------------------------------------

    fun workspaceDetailPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String
    ): HTML.() -> Unit = {
        adminShell(
            pageTitle = workspace.displayName,
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            apps = emptyList(),       // placeholder until Application entity exists
            loggedInAs = loggedInAs
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +workspace.slug }
            }
            div("page-header") {
                div {
                    p("page-title") { +workspace.displayName }
                    p { style = "margin-top:2px;"
                        span("td-code") { +workspace.slug }
                        if (workspace.isMaster) {
                            span("badge badge-blue") {
                                style = "margin-left:0.5rem; vertical-align:middle;"
                                +"Master"
                            }
                        }
                    }
                }
                div {
                    style = "display:flex; gap:0.5rem; align-items:center;"
                    a(
                        href = "/t/${workspace.slug}/login",
                        classes = "btn btn-ghost btn-sm"
                    ) {
                        attributes["target"] = "_blank"
                        +"Open Login ↗"
                    }
                }
            }
            div("alert alert-warn") {
                +"Application management is coming in Phase 2. Use the context panel once apps exist to drill into authentication, users, roles, and more."
            }
            div("card card-body") {
                style = "max-width:480px;"
                table {
                    style = "width:100%;"
                    tbody {
                        detailRow("Slug",              workspace.slug)
                        detailRow("Display Name",      workspace.displayName)
                        detailRow("Issuer URL",        workspace.issuerUrl ?: "— (auto)")
                        detailRow("Token TTL",         "${workspace.tokenExpirySeconds}s")
                        detailRow("Refresh Token TTL", "${workspace.refreshTokenExpirySeconds}s")
                        detailRow("Registration",      if (workspace.registrationEnabled) "Enabled" else "Disabled")
                        detailRow("Email Verification",if (workspace.emailVerificationRequired) "Required" else "Not required")
                        detailRow("Min Password Len",  "${workspace.passwordPolicyMinLength}")
                        detailRow("Accent Color",      workspace.theme.accentColor)
                        detailRow("Logo URL",          workspace.theme.logoUrl ?: "— (default)")
                    }
                }
            }
        }
    }

    private fun TBODY.detailRow(label: String, value: String) {
        tr {
            td { style = "color:var(--muted); width:200px; font-size:0.78rem;"; +label }
            td { style = "font-size:0.85rem;"; +value }
        }
    }
}

/**
 * Holds create-workspace form values for prefill after a failed submission.
 */
data class WorkspacePrefill(
    val slug: String = "",
    val displayName: String = "",
    val issuerUrl: String = "",
    val registrationEnabled: Boolean = true,
    val emailVerificationRequired: Boolean = false,
    val themeAccentColor: String = "#1FBCFF",
    val themeLogoUrl: String = ""
)
