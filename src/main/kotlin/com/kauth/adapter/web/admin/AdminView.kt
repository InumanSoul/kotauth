package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.AccessType
import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Application
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Group
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.Role
import com.kauth.domain.model.Session
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import com.kauth.domain.model.WebhookDelivery
import com.kauth.domain.model.WebhookDeliveryStatus
import com.kauth.domain.model.WebhookEndpoint
import com.kauth.domain.model.WebhookEvent
import kotlinx.html.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    @Volatile
    internal var shellAppInfo: AppInfo = AppInfo()

    fun setShellAppInfo(appInfo: AppInfo) {
        shellAppInfo = appInfo
    }

    // adminHead, adminShell, railItem, ctxLink, and renderXxxCtxPanel
    // have been extracted to AdminShell.kt
    // -------------------------------------------------------------------------



    // -------------------------------------------------------------------------
    // Login page
    // -------------------------------------------------------------------------

    fun loginPage(error: String? = null): HTML.() -> Unit =
        {
            head { adminHead("Login") }
            body {
                div("login-shell") {
                    div("brand") {
                        img(src = "/static/brand/kotauth-negative.svg", alt = "kotauth Brand") {}
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
                            method = FormMethod.post,
                        ) {
                            div("field") {
                                label {
                                    htmlFor = "username"
                                    +"Username"
                                }
                                input(type = InputType.text, name = "username") {
                                    id = "username"
                                    placeholder = "admin"
                                    attributes["autocomplete"] = "username"
                                    required = true
                                    attributes["autofocus"] = "true"
                                }
                            }
                            div("field") {
                                label {
                                    htmlFor = "password"
                                    +"Password"
                                }
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
                    p("copyright") { +"© ${java.time.Year.now()} Powered by kotauth" }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Dashboard — workspace list overview
    // -------------------------------------------------------------------------

    fun dashboardPage(
        workspaces: List<Tenant>,
        loggedInAs: String,
    ): HTML.() -> Unit =
        {
            val wsPairs = workspaces.map { it.slug to it.displayName }
            adminShell(
                pageTitle = "Workspaces",
                activeRail = "apps",
                allWorkspaces = wsPairs,
                workspaceName = "KotAuth",
                workspaceSlug = null, // no workspace selected — ctx panel shows empty state
                loggedInAs = loggedInAs,
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
                                    th { +"Slug" }
                                    th { +"Name" }
                                    th { +"Registration" }
                                    th { +"Token TTL" }
                                    th { +"" }
                                }
                            }
                            tbody {
                                workspaces.forEach { ws ->
                                    tr {
                                        td { span("td-code") { +ws.slug } }
                                        td { +ws.displayName }
                                        td {
                                            if (ws.registrationEnabled) {
                                                span("badge badge-green") { +"Enabled" }
                                            } else {
                                                span("badge badge-red") { +"Disabled" }
                                            }
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
        prefill: WorkspacePrefill = WorkspacePrefill(),
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "New Workspace",
                activeRail = "apps",
                allWorkspaces = allWorkspaces,
                workspaceName = "KotAuth",
                workspaceSlug = null,
                loggedInAs = loggedInAs,
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
                        method = FormMethod.post,
                    ) {
                        p("form-section-title") { +"Identity" }
                        div("field") {
                            label {
                                htmlFor = "slug"
                                +"Slug"
                            }
                            input(type = InputType.text, name = "slug") {
                                id = "slug"
                                placeholder = "my-company"
                                value = prefill.slug
                                required = true
                                attributes["pattern"] = "[a-z0-9-]+"
                            }
                            p(
                                "field-hint",
                            ) { +"Lowercase letters, numbers, hyphens. Used in token URLs: /t/my-company/…" }
                        }
                        div("field") {
                            label {
                                htmlFor = "displayName"
                                +"Display Name"
                            }
                            input(type = InputType.text, name = "displayName") {
                                id = "displayName"
                                placeholder = "Acme Inc"
                                value = prefill.displayName
                                required = true
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "issuerUrl"
                                +"Issuer URL (optional)"
                            }
                            input(type = InputType.url, name = "issuerUrl") {
                                id = "issuerUrl"
                                placeholder = "https://auth.acme.com"
                                value = prefill.issuerUrl
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
                        p("form-section-title") { +"Branding" }
                        div("field") {
                            label {
                                htmlFor = "themeAccentColor"
                                +"Accent Color"
                            }
                            input(type = InputType.color, name = "themeAccentColor") {
                                id = "themeAccentColor"
                                value = prefill.themeAccentColor
                            }
                            p("field-hint") { +"Primary brand color used on the tenant's login page." }
                        }
                        div("field") {
                            label {
                                htmlFor = "themeLogoUrl"
                                +"Logo URL (optional)"
                            }
                            input(type = InputType.url, name = "themeLogoUrl") {
                                id = "themeLogoUrl"
                                placeholder = "https://cdn.acme.com/logo.png"
                                value = prefill.themeLogoUrl
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
        apps: List<Application> = emptyList(),
        loggedInAs: String,
    ): HTML.() -> Unit = workspaceDetailPageImpl(workspace, allWorkspaces, apps, loggedInAs)

    // -------------------------------------------------------------------------
    // Create application form
    // -------------------------------------------------------------------------

    fun createApplicationPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        prefill: ApplicationPrefill = ApplicationPrefill(),
    ): HTML.() -> Unit =
        {
            val appPairs = emptyList<Pair<String, String>>() // no apps yet when creating
            adminShell(
                pageTitle = "New Application — ${workspace.displayName}",
                activeRail = "apps",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                apps = appPairs,
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"New Application" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Create Application" }
                        p("page-subtitle") {
                            +"Register an OAuth2 / OIDC client in the "
                            strong { +workspace.displayName }
                            +" workspace."
                        }
                    }
                }
                if (error != null) {
                    div("alert alert-error") { +error }
                }
                div("form-card") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/applications",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        p("form-section-title") { +"Identity" }
                        div("field") {
                            label {
                                htmlFor = "clientId"
                                +"Client ID"
                            }
                            input(type = InputType.text, name = "clientId") {
                                id = "clientId"
                                placeholder = "my-web-app"
                                value = prefill.clientId
                                required = true
                                attributes["pattern"] = "[a-z0-9-]+"
                            }
                            p("field-hint") {
                                +"Lowercase letters, numbers, hyphens. Unique within this workspace."
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "name"
                                +"Name"
                            }
                            input(type = InputType.text, name = "name") {
                                id = "name"
                                placeholder = "My Web App"
                                value = prefill.name
                                required = true
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "description"
                                +"Description (optional)"
                            }
                            input(type = InputType.text, name = "description") {
                                id = "description"
                                placeholder = "Short description of this application"
                                value = prefill.description
                            }
                        }

                        p("form-section-title") { +"Access" }
                        div("field") {
                            label {
                                htmlFor = "accessType"
                                +"Access Type"
                            }
                            select {
                                name = "accessType"
                                id = "accessType"
                                option {
                                    value = "public"
                                    selected = (prefill.accessType == "public")
                                    +"Public — browser / SPA / mobile (no secret)"
                                }
                                option {
                                    value = "confidential"
                                    selected = (prefill.accessType == "confidential")
                                    +"Confidential — server-side app with a secret"
                                }
                                option {
                                    value = "bearer_only"
                                    selected = (prefill.accessType == "bearer_only")
                                    +"Bearer Only — resource server (validates tokens only)"
                                }
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "redirectUris"
                                +"Redirect URIs"
                            }
                            textArea {
                                name = "redirectUris"
                                id = "redirectUris"
                                rows = "4"
                                attributes["placeholder"] =
                                    "https://app.example.com/callback\nhttps://localhost:3000/callback"
                                +prefill.redirectUris
                            }
                            p("field-hint") { +"One URI per line. Required for Public and Confidential types." }
                        }

                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn") { +"Create Application" }
                            a("/admin/workspaces/${workspace.slug}", classes = "btn btn-ghost") { +"Cancel" }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Application detail
    // -------------------------------------------------------------------------

    fun applicationDetailPage(
        workspace: Tenant,
        application: Application,
        allWorkspaces: List<Pair<String, String>>,
        allApps: List<Application>,
        loggedInAs: String,
        newSecret: String? = null,
    ): HTML.() -> Unit = applicationDetailPageImpl(workspace, application, allWorkspaces, allApps, loggedInAs, newSecret)

    // -------------------------------------------------------------------------
    // Error page — rendered by the StatusPages error boundary instead of
    // a raw HTTP 500. Keeps the full admin shell so the user can navigate away.
    // -------------------------------------------------------------------------

    fun adminErrorPage(
        message: String,
        exceptionType: String? = null,
        allWorkspaces: List<Pair<String, String>> = emptyList(),
        loggedInAs: String = "—",
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Error — KotAuth",
                activeRail = "apps",
                allWorkspaces = allWorkspaces,
                loggedInAs = loggedInAs,
            ) {
                div("page-header") {
                    div {
                        p("page-title") { +"Something went wrong" }
                        p("page-subtitle") { +"An unexpected error occurred processing your request." }
                    }
                }
                div("alert alert-error alert--constrained") {
                    style = "margin-top:1.5rem;"
                    if (exceptionType != null) {
                        p {
                            style = "font-size:0.75rem; opacity:0.65; margin-bottom:0.35rem;"
                            +exceptionType
                        }
                    }
                    p {
                        style = "font-family:monospace; font-size:0.85rem; word-break:break-word;"
                        +message
                    }
                }
                div {
                    style = "margin-top:1.5rem;"
                    a("/admin", classes = "btn btn-ghost") { +"← Back to dashboard" }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Workspace settings form
    // -------------------------------------------------------------------------

    fun workspaceSettingsPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Settings — ${workspace.displayName}",
                activeRail = "settings",
                activeAppSection = "general",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Settings" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Workspace Settings" }
                        p("page-subtitle") { +"Configure tokens, policies, and branding for ${workspace.displayName}." }
                    }
                }

                if (saved) {
                    div("alert alert-success alert--constrained") {
                        +"Settings saved."
                    }
                }
                if (error != null) {
                    div("alert alert-error alert--constrained") {
                        +error
                    }
                }

                div("form-card") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/settings",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        p("form-section-title") { +"Identity" }
                        div("field") {
                            label {
                                htmlFor = "displayName"
                                +"Display Name"
                            }
                            input(type = InputType.text, name = "displayName") {
                                id = "displayName"
                                required = true
                                value = workspace.displayName
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "issuerUrl"
                                +"Issuer URL (optional)"
                            }
                            input(type = InputType.url, name = "issuerUrl") {
                                id = "issuerUrl"
                                placeholder = "https://auth.example.com"
                                value = workspace.issuerUrl ?: ""
                            }
                            p("field-hint") { +"The 'iss' claim in tokens. Defaults to /t/${workspace.slug} if blank." }
                        }

                        p("form-section-title") { +"Token Lifetimes" }
                        div("field") {
                            label {
                                htmlFor = "tokenExpirySeconds"
                                +"Access Token TTL (seconds)"
                            }
                            input(type = InputType.number, name = "tokenExpirySeconds") {
                                id = "tokenExpirySeconds"
                                required = true
                                attributes["min"] = "60"
                                value = workspace.tokenExpirySeconds.toString()
                            }
                            p("field-hint") { +"Minimum 60 seconds. Typical: 3600 (1 hour)." }
                        }
                        div("field") {
                            label {
                                htmlFor = "refreshTokenExpirySeconds"
                                +"Refresh Token TTL (seconds)"
                            }
                            input(type = InputType.number, name = "refreshTokenExpirySeconds") {
                                id = "refreshTokenExpirySeconds"
                                required = true
                                attributes["min"] = "60"
                                value = workspace.refreshTokenExpirySeconds.toString()
                            }
                            p("field-hint") { +"Must be ≥ access token TTL. Typical: 86400 (1 day)." }
                        }

                        p("form-section-title") { +"Registration Policy" }
                        div("checkbox-row") {
                            input(type = InputType.checkBox, name = "registrationEnabled") {
                                id = "registrationEnabled"
                                if (workspace.registrationEnabled) checked = true
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
                                if (workspace.emailVerificationRequired) checked = true
                                attributes["value"] = "true"
                            }
                            label("checkbox-label") {
                                htmlFor = "emailVerificationRequired"
                                +"Require email verification"
                            }
                        }

                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn") { +"Save Settings" }
                            a("/admin/workspaces/${workspace.slug}", classes = "btn btn-ghost") { +"Cancel" }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Security policy page  (/settings/security)
    // -------------------------------------------------------------------------

    fun securityPolicyPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Security Policy — ${workspace.displayName}",
                activeRail = "settings",
                activeAppSection = "security",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}/settings") { +"Settings" }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Security Policy" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Security Policy" }
                        p(
                            "page-subtitle",
                        ) { +"Configure password rules and MFA requirements for ${workspace.displayName}." }
                    }
                }

                if (saved) {
                    div("alert alert-success alert--constrained") {
                        +"Security policy saved."
                    }
                }
                if (error != null) {
                    div("alert alert-error alert--constrained") {
                        +error
                    }
                }

                div("form-card") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/settings/security",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        p("form-section-title") { +"Password Policy" }
                        div("field") {
                            label {
                                htmlFor = "passwordPolicyMinLength"
                                +"Minimum Length"
                            }
                            input(type = InputType.number, name = "passwordPolicyMinLength") {
                                id = "passwordPolicyMinLength"
                                required = true
                                attributes["min"] = "4"
                                attributes["max"] = "128"
                                value = workspace.passwordPolicyMinLength.toString()
                            }
                            p("field-hint") { +"Between 4 and 128 characters." }
                        }
                        div("checkbox-row") {
                            input(type = InputType.checkBox, name = "passwordPolicyRequireSpecial") {
                                id = "passwordPolicyRequireSpecial"
                                if (workspace.passwordPolicyRequireSpecial) checked = true
                                attributes["value"] = "true"
                            }
                            label("checkbox-label") {
                                htmlFor = "passwordPolicyRequireSpecial"
                                +"Require special character (!@#\$%...)"
                            }
                        }
                        div("checkbox-row") {
                            input(type = InputType.checkBox, name = "passwordPolicyRequireUppercase") {
                                id = "passwordPolicyRequireUppercase"
                                if (workspace.passwordPolicyRequireUppercase) checked = true
                                attributes["value"] = "true"
                            }
                            label("checkbox-label") {
                                htmlFor = "passwordPolicyRequireUppercase"
                                +"Require uppercase letter"
                            }
                        }
                        div("checkbox-row") {
                            input(type = InputType.checkBox, name = "passwordPolicyRequireNumber") {
                                id = "passwordPolicyRequireNumber"
                                if (workspace.passwordPolicyRequireNumber) checked = true
                                attributes["value"] = "true"
                            }
                            label("checkbox-label") {
                                htmlFor = "passwordPolicyRequireNumber"
                                +"Require at least one number"
                            }
                        }
                        div("checkbox-row") {
                            input(type = InputType.checkBox, name = "passwordPolicyBlacklistEnabled") {
                                id = "passwordPolicyBlacklistEnabled"
                                if (workspace.passwordPolicyBlacklistEnabled) checked = true
                                attributes["value"] = "true"
                            }
                            label("checkbox-label") {
                                htmlFor = "passwordPolicyBlacklistEnabled"
                                +"Block common/breached passwords"
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "passwordPolicyHistoryCount"
                                +"Password History"
                            }
                            input(type = InputType.number, name = "passwordPolicyHistoryCount") {
                                id = "passwordPolicyHistoryCount"
                                attributes["min"] = "0"
                                attributes["max"] = "24"
                                value = workspace.passwordPolicyHistoryCount.toString()
                            }
                            p("field-hint") { +"Number of previous passwords to remember (0 = disabled, max 24)." }
                        }
                        div("field") {
                            label {
                                htmlFor = "passwordPolicyMaxAgeDays"
                                +"Password Expiry (days)"
                            }
                            input(type = InputType.number, name = "passwordPolicyMaxAgeDays") {
                                id = "passwordPolicyMaxAgeDays"
                                attributes["min"] = "0"
                                attributes["max"] = "365"
                                value = workspace.passwordPolicyMaxAgeDays.toString()
                            }
                            p("field-hint") { +"Force password change after N days (0 = never expires)." }
                        }

                        p("form-section-title") { +"Multi-Factor Authentication" }
                        div("field") {
                            label {
                                htmlFor = "mfaPolicy"
                                +"MFA Policy"
                            }
                            select {
                                name = "mfaPolicy"
                                id = "mfaPolicy"
                                option {
                                    value = "optional"
                                    if (workspace.mfaPolicy ==
                                        "optional"
                                    ) {
                                        selected = true
                                    }
                                    +"Optional"
                                }
                                option {
                                    value = "required"
                                    if (workspace.mfaPolicy ==
                                        "required"
                                    ) {
                                        selected = true
                                    }
                                    +"Required for all users"
                                }
                                option {
                                    value = "required_admins"
                                    if (workspace.mfaPolicy ==
                                        "required_admins"
                                    ) {
                                        selected = true
                                    }
                                    +"Required for admins only"
                                }
                            }
                            p("field-hint") { +"Controls whether users must enroll in TOTP-based MFA." }
                        }

                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn") { +"Save Security Policy" }
                            a(
                                "/admin/workspaces/${workspace.slug}/settings",
                                classes = "btn btn-ghost",
                            ) { +"← General Settings" }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Branding page  (/settings/branding)
    // -------------------------------------------------------------------------

    fun brandingPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Branding — ${workspace.displayName}",
                activeRail = "settings",
                activeAppSection = "branding",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}/settings") { +"Settings" }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Branding" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Branding" }
                        p("page-subtitle") { +"Customize the appearance of ${workspace.displayName}'s auth pages." }
                    }
                }

                if (saved) {
                    div("alert alert-success alert--constrained") {
                        +"Branding saved."
                    }
                }
                if (error != null) {
                    div("alert alert-error alert--constrained") {
                        +error
                    }
                }

                div("form-card form-card--wide") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/settings/branding",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        // ---- Preset picker ----
                        p("form-section-title") { +"Theme Preset" }
                        div("field") {
                            label { +"Apply a preset" }
                            div {
                                style = "display:flex; gap:0.5rem; flex-wrap:wrap; margin-top:0.25rem;"
                                button(type = ButtonType.button, classes = "btn btn-ghost") {
                                    attributes["data-preset"] = "dark"
                                    +"Dark"
                                }
                                button(type = ButtonType.button, classes = "btn btn-ghost") {
                                    attributes["data-preset"] = "light"
                                    +"Light"
                                }
                                button(type = ButtonType.button, classes = "btn btn-ghost") {
                                    attributes["data-preset"] = "simple"
                                    +"Simple"
                                }
                            }
                            p("field-hint") { +"Fills all color fields below with the selected palette. Save to apply." }
                        }

                        // ---- Live preview ----
                        p("form-section-title") { +"Preview" }
                        div("field") {
                            div {
                                style = "border-radius:8px; overflow:hidden; max-width:260px;"
                                div {
                                    id = "previewCard"
                                    style = "padding:1.25rem; border:1px solid #3f3f46; border-top:3px solid #1FBCFF; background:#18181b; border-radius:8px;"
                                    p {
                                        id = "previewTitle"
                                        style = "font-weight:600; font-size:0.85rem; margin:0 0 1rem; color:#fafafa;"
                                        +"Sign in"
                                    }
                                    div {
                                        id = "previewInput"
                                        style = "height:2rem; border-radius:4px; border:1px solid #3f3f46; background:#27272a; margin-bottom:0.75rem;"
                                    }
                                    div {
                                        id = "previewBtn"
                                        style = "height:2rem; border-radius:4px; text-align:center; line-height:2rem; font-size:0.78rem; font-weight:600; background:#1FBCFF; color:#09090b;"
                                        +"Sign in"
                                    }
                                    p {
                                        id = "previewMuted"
                                        style = "font-size:0.75rem; margin:0.75rem 0 0; text-align:center; color:#a1a1aa;"
                                        +"Forgot password?"
                                    }
                                }
                            }
                        }

                        // ---- Color inputs (2-column grid) ----
                        p("form-section-title") { +"Colors" }
                        div {
                            style = "display:grid; grid-template-columns:1fr 1fr; gap:0.75rem 1.5rem;"
                            div("field") {
                                label { htmlFor = "themeAccentColor"; +"Accent" }
                                input(type = InputType.color, name = "themeAccentColor") {
                                    id = "themeAccentColor"
                                    value = workspace.theme.accentColor
                                }
                            }
                            div("field") {
                                label { htmlFor = "themeAccentHover"; +"Accent Hover" }
                                input(type = InputType.color, name = "themeAccentHover") {
                                    id = "themeAccentHover"
                                    value = workspace.theme.accentHoverColor
                                }
                            }
                            div("field") {
                                label { htmlFor = "themeBgDeep"; +"Page Background" }
                                input(type = InputType.color, name = "themeBgDeep") {
                                    id = "themeBgDeep"
                                    value = workspace.theme.bgDeep
                                }
                            }
                            div("field") {
                                label { htmlFor = "themeBgCard"; +"Card Background" }
                                input(type = InputType.color, name = "themeBgCard") {
                                    id = "themeBgCard"
                                    value = workspace.theme.bgCard
                                }
                            }
                            div("field") {
                                label { htmlFor = "themeBgInput"; +"Input Background" }
                                input(type = InputType.color, name = "themeBgInput") {
                                    id = "themeBgInput"
                                    value = workspace.theme.bgInput
                                }
                            }
                            div("field") {
                                label { htmlFor = "themeBorderColor"; +"Border" }
                                input(type = InputType.color, name = "themeBorderColor") {
                                    id = "themeBorderColor"
                                    value = workspace.theme.borderColor
                                }
                            }
                            div("field") {
                                label { htmlFor = "themeTextPrimary"; +"Text Primary" }
                                input(type = InputType.color, name = "themeTextPrimary") {
                                    id = "themeTextPrimary"
                                    value = workspace.theme.textPrimary
                                }
                            }
                            div("field") {
                                label { htmlFor = "themeTextMuted"; +"Text Muted" }
                                input(type = InputType.color, name = "themeTextMuted") {
                                    id = "themeTextMuted"
                                    value = workspace.theme.textMuted
                                }
                            }
                        }

                        div("field") {
                            label { htmlFor = "themeBorderRadius"; +"Border Radius" }
                            input(type = InputType.text, name = "themeBorderRadius") {
                                id = "themeBorderRadius"
                                value = workspace.theme.borderRadius
                                placeholder = "8px"
                            }
                            p("field-hint") { +"Applied to cards, inputs, and buttons on auth pages. e.g. 0px, 6px, 12px." }
                        }

                        // ---- Assets ----
                        p("form-section-title") { +"Assets" }
                        div("field") {
                            label { htmlFor = "themeLogoUrl"; +"Logo URL (optional)" }
                            input(type = InputType.url, name = "themeLogoUrl") {
                                id = "themeLogoUrl"
                                placeholder = "https://cdn.example.com/logo.png"
                                value = workspace.theme.logoUrl ?: ""
                            }
                            p("field-hint") { +"Shown above the login card. Recommended max 180×48px." }
                        }
                        div("field") {
                            label { htmlFor = "themeFaviconUrl"; +"Favicon URL (optional)" }
                            input(type = InputType.url, name = "themeFaviconUrl") {
                                id = "themeFaviconUrl"
                                placeholder = "https://cdn.example.com/favicon.ico"
                                value = workspace.theme.faviconUrl ?: ""
                            }
                        }

                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn") { +"Save Branding" }
                            a(
                                "/admin/workspaces/${workspace.slug}/settings",
                                classes = "btn btn-ghost",
                            ) { +"← General Settings" }
                        }

                        // ---- Script: preset fill + live preview ----
                        script {
                            unsafe {
                                +(
                                    """
(function () {
  var PRESETS = {
    dark: {
      themeAccentColor: '#1FBCFF', themeAccentHover: '#0ea5d9',
      themeBgDeep: '#09090b', themeBgCard: '#18181b', themeBgInput: '#27272a',
      themeBorderColor: '#3f3f46', themeBorderRadius: '8px',
      themeTextPrimary: '#fafafa', themeTextMuted: '#a1a1aa'
    },
    light: {
      themeAccentColor: '#0ea5d9', themeAccentHover: '#0284c7',
      themeBgDeep: '#f8fafc', themeBgCard: '#ffffff', themeBgInput: '#f1f5f9',
      themeBorderColor: '#e2e8f0', themeBorderRadius: '8px',
      themeTextPrimary: '#0f172a', themeTextMuted: '#64748b'
    },
    simple: {
      themeAccentColor: '#212121', themeAccentHover: '#000000',
      themeBgDeep: '#fafafa', themeBgCard: '#ffffff', themeBgInput: '#f1f5f9',
      themeBorderColor: '#e2e8f0', themeBorderRadius: '8px',
      themeTextPrimary: '#0f172a', themeTextMuted: '#64748b'
    }
  };

  function updatePreview() {
    var card   = document.getElementById('previewCard');
    var title  = document.getElementById('previewTitle');
    var inp    = document.getElementById('previewInput');
    var btn    = document.getElementById('previewBtn');
    var muted  = document.getElementById('previewMuted');
    if (!card) return;
    var accent = document.getElementById('themeAccentColor').value;
    var bg     = document.getElementById('themeBgCard').value;
    var bgIn   = document.getElementById('themeBgInput').value;
    var border = document.getElementById('themeBorderColor').value;
    var text   = document.getElementById('themeTextPrimary').value;
    var mutedC = document.getElementById('themeTextMuted').value;
    card.style.background     = bg;
    card.style.borderColor    = border;
    card.style.borderTopColor = accent;
    title.style.color         = text;
    inp.style.background      = bgIn;
    inp.style.borderColor     = border;
    btn.style.background      = accent;
    btn.style.color           = bg;
    muted.style.color         = mutedC;
  }

  document.querySelectorAll('[data-preset]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var p = PRESETS[this.getAttribute('data-preset')];
      if (!p) return;
      Object.keys(p).forEach(function (key) {
        var el = document.getElementById(key);
        if (el) el.value = p[key];
      });
      updatePreview();
    });
  });

  ['themeAccentColor','themeBgCard','themeBgInput','themeBorderColor',
   'themeTextPrimary','themeTextMuted'].forEach(function (id) {
    var el = document.getElementById(id);
    if (el) el.addEventListener('input', updatePreview);
  });

  updatePreview();
})();
                                    """.trimIndent()
                                )
                            }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Edit application form
    // -------------------------------------------------------------------------

    fun editApplicationPage(
        workspace: Tenant,
        application: Application,
        allWorkspaces: List<Pair<String, String>>,
        allApps: List<Application>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit =
        {
            val appPairs = allApps.map { it.clientId to it.name }
            adminShell(
                pageTitle = "Edit ${application.name}",
                activeRail = "apps",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                apps = appPairs,
                activeAppSlug = application.clientId,
                activeAppSection = "overview",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a(
                        "/admin/workspaces/${workspace.slug}/applications/${application.clientId}",
                    ) { +application.clientId }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Edit" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Edit Application" }
                        p("page-subtitle") { +"Update settings for ${application.name}." }
                    }
                }
                if (error != null) {
                    div("alert alert-error alert--constrained") {
                        +error
                    }
                }
                div("form-card") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/applications/${application.clientId}/edit",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        p("form-section-title") { +"Identity" }
                        div("field") {
                            label { +"Client ID" }
                            input(type = InputType.text) {
                                disabled = true
                                value = application.clientId
                            }
                            p("field-hint") { +"Client ID is immutable — it may appear in issued tokens." }
                        }
                        div("field") {
                            label {
                                htmlFor = "name"
                                +"Name"
                            }
                            input(type = InputType.text, name = "name") {
                                id = "name"
                                required = true
                                value = application.name
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "description"
                                +"Description (optional)"
                            }
                            input(type = InputType.text, name = "description") {
                                id = "description"
                                placeholder = "Short description of this application"
                                value = application.description ?: ""
                            }
                        }

                        p("form-section-title") { +"Access" }
                        div("field") {
                            label {
                                htmlFor = "accessType"
                                +"Access Type"
                            }
                            select {
                                name = "accessType"
                                id = "accessType"
                                option {
                                    value = "public"
                                    selected = (application.accessType == AccessType.PUBLIC)
                                    +"Public — browser / SPA / mobile (no secret)"
                                }
                                option {
                                    value = "confidential"
                                    selected = (application.accessType == AccessType.CONFIDENTIAL)
                                    +"Confidential — server-side app with a secret"
                                }
                                option {
                                    value = "bearer_only"
                                    selected = (application.accessType == AccessType.BEARER_ONLY)
                                    +"Bearer Only — resource server (validates tokens only)"
                                }
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "redirectUris"
                                +"Redirect URIs"
                            }
                            textArea {
                                name = "redirectUris"
                                id = "redirectUris"
                                rows = "4"
                                attributes["placeholder"] =
                                    "https://app.example.com/callback\nhttps://localhost:3000/callback"
                                +application.redirectUris.joinToString("\n")
                            }
                            p("field-hint") { +"One URI per line." }
                        }

                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn") { +"Save Changes" }
                            a(
                                "/admin/workspaces/${workspace.slug}/applications/${application.clientId}",
                                classes = "btn btn-ghost",
                            ) { +"Cancel" }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // User list
    // -------------------------------------------------------------------------

    fun userListPage(
        workspace: Tenant,
        users: List<User>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        search: String? = null,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Users — ${workspace.displayName}",
                activeRail = "directory",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "users",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Users" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Users" }
                        p(
                            "page-subtitle",
                        ) { +"${users.size} user${if (users.size != 1) "s" else ""} in this workspace" }
                    }
                    a(
                        href = "/admin/workspaces/${workspace.slug}/users/new",
                        classes = "btn btn-sm",
                    ) { +"+ New User" }
                }

                // Search bar
                form(
                    action = "/admin/workspaces/${workspace.slug}/users",
                    method = FormMethod.get,
                    classes = "search-form",
                ) {
                    div("search-row") {
                        input(type = InputType.search, name = "q") {
                            id = "q"
                            placeholder = "Search by username, email, or name…"
                            value = search ?: ""
                        }
                        button(type = ButtonType.submit, classes = "btn btn-sm") { +"Search" }
                        if (search != null) {
                            a("/admin/workspaces/${workspace.slug}/users", classes = "btn btn-ghost btn-sm") {
                                +"Clear"
                            }
                        }
                    }
                }

                div("card") {
                    if (users.isEmpty()) {
                        div("empty-state") {
                            div("empty-state-icon") { +"◷" }
                            p("empty-state-text") {
                                if (search != null) {
                                    +"No users match \"$search\"."
                                } else {
                                    +"No users yet."
                                }
                            }
                        }
                    } else {
                        table {
                            thead {
                                tr {
                                    th { +"Username" }
                                    th { +"Full Name" }
                                    th { +"Email" }
                                    th { +"Status" }
                                    th { +"" }
                                }
                            }
                            tbody {
                                users.forEach { user ->
                                    tr {
                                        td { span("td-code") { +user.username } }
                                        td { +user.fullName }
                                        td { +user.email }
                                        td {
                                            if (user.enabled) {
                                                span("badge badge-green") { +"Active" }
                                            } else {
                                                span("badge badge-red") { +"Disabled" }
                                            }
                                        }
                                        td {
                                            a(
                                                href = "/admin/workspaces/${workspace.slug}/users/${user.id}",
                                                classes = "btn btn-ghost btn-sm",
                                            ) { +"Open →" }
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
    // Create user form
    // -------------------------------------------------------------------------

    fun createUserPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        prefill: UserPrefill = UserPrefill(),
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "New User — ${workspace.displayName}",
                activeRail = "directory",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "users",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}/users") { +"Users" }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"New User" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Create User" }
                        p("page-subtitle") {
                            +"Add a user to the "
                            strong { +workspace.displayName }
                            +" workspace. The account will be pre-verified."
                        }
                    }
                }
                if (error != null) {
                    div("alert alert-error alert--constrained") {
                        +error
                    }
                }
                div("form-card") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/users",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        div("field") {
                            label {
                                htmlFor = "username"
                                +"Username"
                            }
                            input(type = InputType.text, name = "username") {
                                id = "username"
                                required = true
                                value = prefill.username
                                placeholder = "johndoe"
                                attributes["pattern"] = "[a-zA-Z0-9._-]+"
                            }
                            p(
                                "field-hint",
                            ) { +"Letters, digits, dots, underscores, hyphens. Immutable after creation." }
                        }
                        div("field") {
                            label {
                                htmlFor = "email"
                                +"Email"
                            }
                            input(type = InputType.email, name = "email") {
                                id = "email"
                                required = true
                                value = prefill.email
                                placeholder = "john@example.com"
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "fullName"
                                +"Full Name"
                            }
                            input(type = InputType.text, name = "fullName") {
                                id = "fullName"
                                value = prefill.fullName
                                placeholder = "John Doe"
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "password"
                                +"Password"
                            }
                            input(type = InputType.password, name = "password") {
                                id = "password"
                                required = true
                                placeholder = "Minimum 4 characters"
                            }
                            p("field-hint") { +"Minimum 4 characters. The user can change it after login." }
                        }
                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn") { +"Create User" }
                            a("/admin/workspaces/${workspace.slug}/users", classes = "btn btn-ghost") { +"Cancel" }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // User detail / edit
    // -------------------------------------------------------------------------

    fun userDetailPage(
        workspace: Tenant,
        user: User,
        sessions: List<Session>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        successMessage: String? = null,
        editError: String? = null,
    ): HTML.() -> Unit = userDetailPageImpl(workspace, user, sessions, allWorkspaces, loggedInAs, successMessage, editError)

    // -------------------------------------------------------------------------
    // Active sessions (workspace-wide)
    // -------------------------------------------------------------------------

    fun activeSessionsPage(
        workspace: Tenant,
        sessions: List<Session>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Sessions — ${workspace.displayName}",
                activeRail = "security",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "sessions",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Sessions" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Active Sessions" }
                        p(
                            "page-subtitle",
                        ) { +"${sessions.size} active session${if (sessions.size != 1) "s" else ""} in this workspace" }
                    }
                }
                div("card") {
                    if (sessions.isEmpty()) {
                        div("empty-state") {
                            div("empty-state-icon") { +"⊘" }
                            p("empty-state-text") { +"No active sessions." }
                        }
                    } else {
                        table {
                            thead {
                                tr {
                                    th { +"Session ID" }
                                    th { +"User" }
                                    th { +"Client" }
                                    th { +"IP Address" }
                                    th { +"Created" }
                                    th { +"Expires" }
                                    th { +"" }
                                }
                            }
                            tbody {
                                sessions.forEach { s ->
                                    tr {
                                        td { span("td-code") { +"#${s.id}" } }
                                        td { span("td-muted") { +(s.userId?.toString() ?: "M2M") } }
                                        td { span("td-muted") { +(s.clientId?.toString() ?: "—") } }
                                        td { span("td-code") { +(s.ipAddress ?: "—") } }
                                        td { span("td-muted") { +s.createdAt.toDisplayString() } }
                                        td { span("td-muted") { +s.expiresAt.toDisplayString() } }
                                        td {
                                            form(
                                                action = "/admin/workspaces/${workspace.slug}/sessions/${s.id}/revoke",
                                                method = FormMethod.post,
                                                classes = "inline-form",
                                            ) {
                                                button(type = ButtonType.submit, classes = "btn btn-ghost btn-sm") {
                                                    +"Revoke"
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
        }

    // -------------------------------------------------------------------------
    // Audit log
    // -------------------------------------------------------------------------

    fun auditLogPage(
        workspace: Tenant,
        events: List<AuditEvent>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        page: Int = 1,
        totalPages: Int = 1,
        eventTypeFilter: String? = null,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Audit Log — ${workspace.displayName}",
                activeRail = "logs",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "events",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Audit Log" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Audit Log" }
                        p("page-subtitle") { +"Security-relevant events for the ${workspace.displayName} workspace." }
                    }
                }

                // Filter bar
                form(
                    action = "/admin/workspaces/${workspace.slug}/logs",
                    method = FormMethod.get,
                    classes = "search-form",
                ) {
                    div("search-row") {
                        select {
                            name = "event"
                            option {
                                value = ""
                                selected = (eventTypeFilter == null)
                                +"All events"
                            }
                            AuditEventType.entries.forEach { type ->
                                option {
                                    value = type.name
                                    selected = (type.name == eventTypeFilter)
                                    +type.name.lowercase().replace('_', ' ')
                                }
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn btn-sm") { +"Filter" }
                        if (eventTypeFilter != null) {
                            a("/admin/workspaces/${workspace.slug}/logs", classes = "btn btn-ghost btn-sm") {
                                +"Clear"
                            }
                        }
                    }
                }

                div("card") {
                    if (events.isEmpty()) {
                        div("empty-state") {
                            div("empty-state-icon") { +"⊘" }
                            p("empty-state-text") { +"No events found." }
                        }
                    } else {
                        table {
                            thead {
                                tr {
                                    th { +"Time" }
                                    th { +"Event" }
                                    th { +"User" }
                                    th { +"Client" }
                                    th { +"IP" }
                                }
                            }
                            tbody {
                                events.forEach { e ->
                                    tr {
                                        td { span("td-muted") { +e.createdAt.toDisplayString() } }
                                        td {
                                            span("td-code") {
                                                style = "font-size:0.75rem;"
                                                +e.eventType.name
                                            }
                                        }
                                        td { span("td-muted") { +(e.userId?.toString() ?: "—") } }
                                        td { span("td-muted") { +(e.clientId?.toString() ?: "—") } }
                                        td { span("td-muted") { +(e.ipAddress ?: "—") } }
                                    }
                                }
                            }
                        }
                    }
                }

                // Pagination
                if (totalPages > 1) {
                    div("pagination") {
                        val baseUrl =
                            "/admin/workspaces/${workspace.slug}/logs" +
                                (if (eventTypeFilter != null) "?event=$eventTypeFilter&" else "?")
                        if (page > 1) {
                            a("${baseUrl}page=${page - 1}", classes = "btn btn-ghost btn-sm") { +"← Prev" }
                        }
                        span("pagination-label") {
                            +"Page $page of $totalPages"
                        }
                        if (page < totalPages) {
                            a("${baseUrl}page=${page + 1}", classes = "btn btn-ghost btn-sm") { +"Next →" }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // SMTP settings (Phase 3b)
    // -------------------------------------------------------------------------

    /**
     * Per-workspace SMTP configuration page.
     *
     * Password field behaviour: when blank, the existing encrypted password is preserved.
     * The hint communicates this to the operator so they don't accidentally clear it.
     */
    fun smtpSettingsPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "SMTP — ${workspace.displayName}",
                activeRail = "settings",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                loggedInAs = loggedInAs,
                activeAppSection = "smtp",
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}/settings") { +"Settings" }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"SMTP" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"SMTP Settings" }
                        p("page-subtitle") {
                            +"Configure outbound email for ${workspace.displayName}. Used for verification and password reset emails."
                        }
                    }
                }

                if (saved) {
                    div("alert alert-success alert--constrained") {
                        +"SMTP settings saved."
                    }
                }
                if (error != null) {
                    div("alert alert-error alert--constrained") {
                        +error
                    }
                }

                div("form-card") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/settings/smtp",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        div("checkbox-row") {
                            input(type = InputType.checkBox, name = "smtpEnabled") {
                                id = "smtpEnabled"
                                if (workspace.smtpEnabled) checked = true
                                attributes["value"] = "true"
                            }
                            label("checkbox-label") {
                                htmlFor = "smtpEnabled"
                                +"Enable email delivery"
                            }
                        }

                        p("form-section-title") { +"Server" }
                        div("field") {
                            label {
                                htmlFor = "smtpHost"
                                +"Host"
                            }
                            input(type = InputType.text, name = "smtpHost") {
                                id = "smtpHost"
                                placeholder = "smtp.example.com"
                                value = workspace.smtpHost ?: ""
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "smtpPort"
                                +"Port"
                            }
                            input(type = InputType.number, name = "smtpPort") {
                                id = "smtpPort"
                                placeholder = "587"
                                attributes["min"] = "1"
                                attributes["max"] = "65535"
                                value = workspace.smtpPort.toString()
                            }
                            p("field-hint") { +"Common ports: 25 (SMTP), 465 (SMTPS), 587 (STARTTLS)." }
                        }
                        div("checkbox-row") {
                            input(type = InputType.checkBox, name = "smtpTlsEnabled") {
                                id = "smtpTlsEnabled"
                                if (workspace.smtpTlsEnabled) checked = true
                                attributes["value"] = "true"
                            }
                            label("checkbox-label") {
                                htmlFor = "smtpTlsEnabled"
                                +"Enable TLS / STARTTLS"
                            }
                        }

                        p("form-section-title") { +"Authentication" }
                        div("field") {
                            label {
                                htmlFor = "smtpUsername"
                                +"Username"
                            }
                            input(type = InputType.text, name = "smtpUsername") {
                                id = "smtpUsername"
                                placeholder = "user@example.com"
                                attributes["autocomplete"] = "off"
                                value = workspace.smtpUsername ?: ""
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "smtpPassword"
                                +"Password"
                            }
                            input(type = InputType.password, name = "smtpPassword") {
                                id = "smtpPassword"
                                attributes["autocomplete"] = "new-password"
                                // Never pre-fill — password is encrypted at rest
                            }
                            p("field-hint") {
                                if (workspace.smtpPassword != null) {
                                    +"A password is already set. Leave blank to keep the existing password."
                                } else {
                                    +"Enter the SMTP password. It is stored encrypted."
                                }
                            }
                        }

                        p("form-section-title") { +"Sender" }
                        div("field") {
                            label {
                                htmlFor = "smtpFromAddress"
                                +"From address"
                            }
                            input(type = InputType.email, name = "smtpFromAddress") {
                                id = "smtpFromAddress"
                                placeholder = "noreply@example.com"
                                value = workspace.smtpFromAddress ?: ""
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "smtpFromName"
                                +"From name (optional)"
                            }
                            input(type = InputType.text, name = "smtpFromName") {
                                id = "smtpFromName"
                                placeholder = "My App"
                                value = workspace.smtpFromName ?: ""
                            }
                        }

                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn") { +"Save SMTP Settings" }
                            a(
                                "/admin/workspaces/${workspace.slug}/settings",
                                classes = "btn btn-ghost",
                            ) { +"Back to Settings" }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Roles list (Phase 3c)
    // -------------------------------------------------------------------------

    fun rolesListPage(
        workspace: Tenant,
        roles: List<Role>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Roles — ${workspace.displayName}",
                activeRail = "directory",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "roles",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Roles" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Roles" }
                        p(
                            "page-subtitle",
                        ) { +"${roles.size} role${if (roles.size != 1) "s" else ""} in this workspace" }
                    }
                    a(
                        href = "/admin/workspaces/${workspace.slug}/roles/create",
                        classes = "btn",
                    ) { +"+ Create Role" }
                }

                div("card") {
                    if (roles.isEmpty()) {
                        div("empty-state") {
                            div("empty-state-icon") { +"◎" }
                            p("empty-state-text") { +"No roles defined yet." }
                        }
                    } else {
                        table {
                            thead {
                                tr {
                                    th { +"Name" }
                                    th { +"Scope" }
                                    th { +"Description" }
                                    th { +"Composite" }
                                    th { +"" }
                                }
                            }
                            tbody {
                                roles.forEach { role ->
                                    tr {
                                        td { span("td-code") { +(role.name) } }
                                        td {
                                            val isWorkspace = role.scope.value == "tenant"
                                            span("badge badge-${if (isWorkspace) "green" else "blue"}") {
                                                +(if (isWorkspace) "workspace" else "application")
                                            }
                                        }
                                        td { +(role.description ?: "—") }
                                        td {
                                            +(if (role.childRoleIds.isNotEmpty()) "${role.childRoleIds.size} children" else "—")
                                        }
                                        td {
                                            a(
                                                href = "/admin/workspaces/${workspace.slug}/roles/${role.id}",
                                                classes = "btn btn-ghost btn-sm",
                                            ) { +"Open →" }
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
    // Create role page (Phase 3c)
    // -------------------------------------------------------------------------

    fun createRolePage(
        workspace: Tenant,
        apps: List<Application>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "New Role — ${workspace.displayName}",
                activeRail = "directory",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "roles",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}/roles") { +"Roles" }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"New Role" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Create Role" }
                        p("page-subtitle") {
                            +"Add a role to the "
                            strong { +workspace.displayName }
                            +" workspace."
                        }
                    }
                }
                if (error != null) {
                    div("alert alert-error alert--constrained") {
                        +error
                    }
                }
                div("form-card") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/roles",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        div("field") {
                            label {
                                htmlFor = "roleName"
                                +"Name"
                            }
                            input(type = InputType.text, name = "name") {
                                id = "roleName"
                                required = true
                                placeholder = "e.g. admin, editor, viewer"
                                attributes["pattern"] = "[a-zA-Z0-9._-]+"
                            }
                            p("field-hint") { +"Letters, digits, dots, underscores, hyphens only." }
                        }
                        div("field") {
                            label {
                                htmlFor = "roleDesc"
                                +"Description (optional)"
                            }
                            input(type = InputType.text, name = "description") {
                                id = "roleDesc"
                                placeholder = "Short description of what this role grants"
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "roleScope"
                                +"Scope"
                            }
                            select {
                                name = "scope"
                                id = "roleScope"
                                attributes["onchange"] =
                                    "document.getElementById('appField').style.display=this.value==='client'?'block':'none'"
                                option {
                                    value = "tenant"
                                    +"Workspace (realm-level)"
                                }
                                option {
                                    value = "client"
                                    +"Application (app-scoped)"
                                }
                            }
                            p("field-hint") {
                                +"Workspace roles apply across the entire workspace. Application roles are scoped to a specific app."
                            }
                        }
                        div("field") {
                            id = "appField"
                            style = "display:none;"
                            label {
                                htmlFor = "clientId"
                                +"Application"
                            }
                            if (apps.isEmpty()) {
                                p("field-hint") { +"No applications in this workspace yet. Create one first." }
                            } else {
                                select {
                                    name = "clientId"
                                    id = "clientId"
                                    option {
                                        value = ""
                                        +"— select application —"
                                    }
                                    apps.forEach { app ->
                                        option {
                                            value = app.id.toString()
                                            +app.name
                                        }
                                    }
                                }
                            }
                        }
                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn") { +"Create Role" }
                            a("/admin/workspaces/${workspace.slug}/roles", classes = "btn btn-ghost") { +"Cancel" }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Role detail (Phase 3c)
    // -------------------------------------------------------------------------

    fun roleDetailPage(
        workspace: Tenant,
        role: Role,
        allRoles: List<Role>,
        allUsers: List<User>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "${role.name} — Roles",
                activeRail = "directory",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "roles",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}/roles") { +"Roles" }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +role.name }
                }
                div("page-header") {
                    div {
                        p("page-title") { +role.name }
                        p("page-subtitle") { +"${role.scope.value} role · ${role.description ?: "no description"}" }
                    }
                    form(
                        action = "/admin/workspaces/${workspace.slug}/roles/${role.id}/delete",
                        method = FormMethod.post,
                        classes = "inline-form",
                    ) {
                        button(type = ButtonType.submit, classes = "btn btn-ghost btn-sm") {
                            attributes["onclick"] = "return confirm('Delete role ${role.name}?')"
                            +"Delete"
                        }
                    }
                }

                // Edit name/description
                div("form-card form-card--wide card--spaced") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/roles/${role.id}/edit",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        p("form-section-title") { +"Edit Role" }
                        div("field") {
                            label {
                                htmlFor = "roleName"
                                +"Name"
                            }
                            input(type = InputType.text, name = "name") {
                                id = "roleName"
                                required = true
                                value =
                                    role.name
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "roleDesc"
                                +"Description"
                            }
                            input(type = InputType.text, name = "description") {
                                id = "roleDesc"
                                value =
                                    role.description ?: ""
                            }
                        }
                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn btn-sm") { +"Save" }
                        }
                    }
                }

                // Composite children
                div("card card--spaced") {
                    p("form-section-title") { +"Composite Children" }
                    if (role.childRoleIds.isEmpty()) {
                        p("card-empty-msg") {
                            +"No child roles."
                        }
                    } else {
                        table {
                            thead {
                                tr {
                                    th { +"Child Role" }
                                    th { +"" }
                                }
                            }
                            tbody {
                                role.childRoleIds.forEach { childId ->
                                    val child = allRoles.find { it.id == childId }
                                    tr {
                                        td { +(child?.name ?: "#$childId") }
                                        td {
                                            form(
                                                action = "/admin/workspaces/${workspace.slug}/roles/${role.id}/remove-child",
                                                method = FormMethod.post,
                                                classes = "inline-form",
                                            ) {
                                                input(type = InputType.hidden, name = "childRoleId") {
                                                    value =
                                                        childId.toString()
                                                }
                                                button(
                                                    type = ButtonType.submit,
                                                    classes = "btn btn-ghost btn-sm",
                                                ) { +"Remove" }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Add child form
                    val availableChildren = allRoles.filter { it.id != role.id && it.id !in role.childRoleIds }
                    if (availableChildren.isNotEmpty()) {
                        form(
                            action = "/admin/workspaces/${workspace.slug}/roles/${role.id}/children",
                            method = FormMethod.post,
                            classes = "card-add-row",
                        ) {
                            select {
                                name = "childRoleId"
                                availableChildren.forEach { r ->
                                    option {
                                        value = r.id.toString()
                                        +r.name
                                    }
                                }
                            }
                            button(type = ButtonType.submit, classes = "btn btn-sm") { +"Add Child" }
                        }
                    }
                }

                // Assign user
                div("card") {
                    p("form-section-title") { +"Assigned Users" }
                    form(
                        action = "/admin/workspaces/${workspace.slug}/roles/${role.id}/assign-user",
                        method = FormMethod.post,
                        classes = "card-add-row",
                    ) {
                        select {
                            name = "userId"
                            allUsers.forEach { u ->
                                option {
                                    value = u.id.toString()
                                    +"${u.username} (${u.email})"
                                }
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn btn-sm") { +"Assign" }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Groups list (Phase 3c)
    // -------------------------------------------------------------------------

    fun groupsListPage(
        workspace: Tenant,
        groups: List<Group>,
        roles: List<Role>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Groups — ${workspace.displayName}",
                activeRail = "directory",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "groups",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Groups" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Groups" }
                        p(
                            "page-subtitle",
                        ) { +"${groups.size} group${if (groups.size != 1) "s" else ""} in this workspace" }
                    }
                    a(
                        href = "/admin/workspaces/${workspace.slug}/groups/create",
                        classes = "btn",
                    ) { +"+ Create Group" }
                }

                div("card") {
                    if (groups.isEmpty()) {
                        div("empty-state") {
                            div("empty-state-icon") { +"◫" }
                            p("empty-state-text") { +"No groups defined yet." }
                        }
                    } else {
                        table {
                            thead {
                                tr {
                                    th { +"Name" }
                                    th { +"Parent" }
                                    th { +"Roles" }
                                    th { +"Description" }
                                    th { +"" }
                                }
                            }
                            tbody {
                                groups.forEach { group ->
                                    val parent = groups.find { it.id == group.parentGroupId }
                                    val roleNames =
                                        group.roleIds.mapNotNull { rid ->
                                            roles.find { it.id == rid }?.name
                                        }
                                    tr {
                                        td { span("td-code") { +group.name } }
                                        td { +(parent?.name ?: "—") }
                                        td { +(if (roleNames.isNotEmpty()) roleNames.joinToString(", ") else "—") }
                                        td { +(group.description ?: "—") }
                                        td {
                                            a(
                                                href = "/admin/workspaces/${workspace.slug}/groups/${group.id}",
                                                classes = "btn btn-ghost btn-sm",
                                            ) { +"Open →" }
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
    // Create group page (Phase 3c)
    // -------------------------------------------------------------------------

    fun createGroupPage(
        workspace: Tenant,
        groups: List<Group>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        error: String? = null,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "New Group — ${workspace.displayName}",
                activeRail = "directory",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "groups",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}/groups") { +"Groups" }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"New Group" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Create Group" }
                        p("page-subtitle") {
                            +"Add a group to the "
                            strong { +workspace.displayName }
                            +" workspace."
                        }
                    }
                }
                if (error != null) {
                    div("alert alert-error alert--constrained") {
                        +error
                    }
                }
                div("form-card") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/groups",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        div("field") {
                            label {
                                htmlFor = "groupName"
                                +"Name"
                            }
                            input(type = InputType.text, name = "name") {
                                id = "groupName"
                                required = true
                                placeholder = "e.g. engineering, marketing, ops"
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "groupDesc"
                                +"Description (optional)"
                            }
                            input(type = InputType.text, name = "description") {
                                id = "groupDesc"
                                placeholder = "What this group represents"
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "parentGroup"
                                +"Parent Group (optional)"
                            }
                            select {
                                name = "parentGroupId"
                                id = "parentGroup"
                                option {
                                    value = ""
                                    +"— None (top-level) —"
                                }
                                groups.forEach { g ->
                                    option {
                                        value = g.id.toString()
                                        +g.name
                                    }
                                }
                            }
                            p("field-hint") { +"Nested groups inherit the roles assigned to their parent." }
                        }
                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn") { +"Create Group" }
                            a("/admin/workspaces/${workspace.slug}/groups", classes = "btn btn-ghost") { +"Cancel" }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Group detail (Phase 3c)
    // -------------------------------------------------------------------------

    fun groupDetailPage(
        workspace: Tenant,
        group: Group,
        allGroups: List<Group>,
        allRoles: List<Role>,
        members: List<User>,
        allUsers: List<User>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "${group.name} — Groups",
                activeRail = "directory",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "groups",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}/groups") { +"Groups" }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +group.name }
                }
                div("page-header") {
                    div {
                        p("page-title") { +group.name }
                        p("page-subtitle") {
                            val parent = allGroups.find { it.id == group.parentGroupId }
                            +(if (parent != null) "Child of ${parent.name}" else "Top-level group")
                            +" · ${group.description ?: "no description"}"
                        }
                    }
                    form(
                        action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/delete",
                        method = FormMethod.post,
                        classes = "inline-form",
                    ) {
                        button(type = ButtonType.submit, classes = "btn btn-ghost btn-sm") {
                            attributes["onclick"] = "return confirm('Delete group ${group.name}?')"
                            +"Delete"
                        }
                    }
                }

                // Edit name/description
                div("form-card form-card--wide card--spaced") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/edit",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        p("form-section-title") { +"Edit Group" }
                        div("field") {
                            label {
                                htmlFor = "gName"
                                +"Name"
                            }
                            input(type = InputType.text, name = "name") {
                                id = "gName"
                                required = true
                                value = group.name
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "gDesc"
                                +"Description"
                            }
                            input(type = InputType.text, name = "description") {
                                id = "gDesc"
                                value =
                                    group.description ?: ""
                            }
                        }
                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn btn-sm") { +"Save" }
                        }
                    }
                }

                // Assigned roles
                div("card card--spaced") {
                    p("form-section-title") { +"Assigned Roles" }
                    if (group.roleIds.isEmpty()) {
                        p("card-empty-msg") {
                            +"No roles assigned."
                        }
                    } else {
                        table {
                            thead {
                                tr {
                                    th { +"Role" }
                                    th { +"Scope" }
                                    th { +"" }
                                }
                            }
                            tbody {
                                group.roleIds.forEach { rid ->
                                    val r = allRoles.find { it.id == rid }
                                    tr {
                                        td { +(r?.name ?: "#$rid") }
                                        td { span("badge badge-green") { +(r?.scope?.value ?: "?") } }
                                        td {
                                            form(
                                                action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/unassign-role",
                                                method = FormMethod.post,
                                                classes = "inline-form",
                                            ) {
                                                input(
                                                    type = InputType.hidden,
                                                    name = "roleId",
                                                ) { value = rid.toString() }
                                                button(
                                                    type = ButtonType.submit,
                                                    classes = "btn btn-ghost btn-sm",
                                                ) { +"Remove" }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val availableRoles = allRoles.filter { it.id !in group.roleIds }
                    if (availableRoles.isNotEmpty()) {
                        form(
                            action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/assign-role",
                            method = FormMethod.post,
                            classes = "card-add-row",
                        ) {
                            select {
                                name = "roleId"
                                availableRoles.forEach { r ->
                                    option {
                                        value = r.id.toString()
                                        +r.name
                                    }
                                }
                            }
                            button(type = ButtonType.submit, classes = "btn btn-sm") { +"Assign Role" }
                        }
                    }
                }

                // Members
                div("card") {
                    p("form-section-title") { +"Members (${members.size})" }
                    if (members.isEmpty()) {
                        p("card-empty-msg") {
                            +"No members."
                        }
                    } else {
                        table {
                            thead {
                                tr {
                                    th { +"Username" }
                                    th { +"Email" }
                                    th { +"" }
                                }
                            }
                            tbody {
                                members.forEach { u ->
                                    tr {
                                        td { span("td-code") { +u.username } }
                                        td { +u.email }
                                        td {
                                            form(
                                                action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/remove-member",
                                                method = FormMethod.post,
                                                classes = "inline-form",
                                            ) {
                                                input(type = InputType.hidden, name = "userId") {
                                                    value =
                                                        u.id.toString()
                                                }
                                                button(
                                                    type = ButtonType.submit,
                                                    classes = "btn btn-ghost btn-sm",
                                                ) { +"Remove" }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Add member
                    val nonMembers = allUsers.filter { u -> members.none { it.id == u.id } }
                    if (nonMembers.isNotEmpty()) {
                        form(
                            action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/add-member",
                            method = FormMethod.post,
                            classes = "card-add-row",
                        ) {
                            select {
                                name = "userId"
                                nonMembers.forEach { u ->
                                    option {
                                        value = u.id.toString()
                                        +"${u.username} (${u.email})"
                                    }
                                }
                            }
                            button(type = ButtonType.submit, classes = "btn btn-sm") { +"Add Member" }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // MFA settings (Phase 3c)
    // -------------------------------------------------------------------------

    fun mfaSettingsPage(
        workspace: Tenant,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        totalUsers: Int = 0,
        enrolledUsers: Int = 0,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "MFA — ${workspace.displayName}",
                activeRail = "security",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                activeAppSection = "mfa",
                loggedInAs = loggedInAs,
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"MFA" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Multi-Factor Authentication" }
                        p("page-subtitle") { +"TOTP-based MFA for ${workspace.displayName}." }
                    }
                }

                // Stats
                div("card card--spaced") {
                    style = "padding:1.25rem;"
                    div {
                        style = "display:flex; gap:2rem;"
                        div {
                            p("td-muted") { +"Current Policy" }
                            p("page-title") {
                                style = "font-size:1.1rem;"
                                +when (workspace.mfaPolicy) {
                                    "required" -> "Required"
                                    "required_admins" -> "Required (admins)"
                                    else -> "Optional"
                                }
                            }
                        }
                        div {
                            p("td-muted") { +"Enrolled Users" }
                            p("page-title") {
                                style = "font-size:1.1rem;"
                                +"$enrolledUsers / $totalUsers"
                            }
                        }
                        div {
                            p("td-muted") { +"Enrollment Rate" }
                            p("page-title") {
                                style = "font-size:1.1rem;"
                                +if (totalUsers > 0) "${(enrolledUsers * 100 / totalUsers)}%" else "—"
                            }
                        }
                    }
                }

                div("form-card form-card--wide") {
                    p("form-section-title") { +"Configuration" }
                    p("td-muted") {
                        style = "padding:0 0.75rem 1rem;"
                        +"The MFA policy is managed in workspace settings. "
                        +"Users enroll via their account portal at /t/${workspace.slug}/account/mfa/enroll."
                    }
                    div("form-actions") {
                        a(
                            "/admin/workspaces/${workspace.slug}/settings",
                            classes = "btn btn-sm",
                        ) { +"Go to Workspace Settings" }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Identity Providers settings page — Phase 2 (Social Login)
    // -------------------------------------------------------------------------

    /**
     * Displays the Identity Providers configuration page for a tenant.
     * Shows a list of supported providers (Google, GitHub) with their current
     * configuration status and a form to add/update each provider.
     *
     * @param workspace     The tenant being configured.
     * @param providers     Currently configured providers for this tenant.
     * @param allWorkspaces Sidebar workspace list.
     * @param loggedInAs    Currently logged-in admin username.
     * @param editProvider  If set, expand the inline edit form for this provider.
     * @param error         Error message from a failed save attempt.
     * @param saved         True after a successful save.
     */
    fun identityProvidersPage(
        workspace: Tenant,
        providers: List<IdentityProvider>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        editProvider: SocialProvider? = null,
        error: String? = null,
        saved: Boolean = false,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Identity Providers — ${workspace.displayName}",
                activeRail = "settings",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                loggedInAs = loggedInAs,
                activeAppSection = "identity-providers",
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}/settings") { +"Settings" }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Identity Providers" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Identity Providers" }
                        p("page-subtitle") {
                            +"Configure social login providers for ${workspace.displayName}. "
                            +"Users can sign in with their Google or GitHub accounts."
                        }
                    }
                }

                if (saved) {
                    div("alert alert-success alert--constrained") {
                        +"Identity provider settings saved."
                    }
                }
                if (error != null) {
                    div("alert alert-error alert--constrained") {
                        +error
                    }
                }

                val providerMap = providers.associateBy { it.provider }

                for (prov in SocialProvider.entries) {
                    val existing = providerMap[prov]
                    val isConfigured = existing != null
                    val isEditing = editProvider == prov

                    div("form-card form-card--wide card--spaced") {
                            div {
                            style = "display:flex; align-items:center; gap:1rem; margin-bottom:1rem;"
                            p("form-section-title") {
                                style = "margin:0;"
                                +prov.displayName
                            }
                            if (isConfigured) {
                                val enabledBadge = if (existing!!.enabled) "badge-green" else "badge-neutral"
                                val enabledText = if (existing.enabled) "Enabled" else "Disabled"
                                span("badge $enabledBadge") { +enabledText }
                            } else {
                                span("badge badge-neutral") { +"Not configured" }
                            }
                        }

                        p("field-hint") {
                            style = "margin-bottom:1rem;"
                            when (prov) {
                                SocialProvider.GOOGLE -> {
                                    +"Create credentials in "
                                    a(
                                        href = "https://console.cloud.google.com/apis/credentials",
                                        target = "_blank",
                                    ) { +"Google Cloud Console" }
                                    +". Set the authorized redirect URI to: "
                                    code {
                                        +"${workspace.issuerUrl ?: "https://your-domain.com"}/t/${workspace.slug}/auth/social/google/callback"
                                    }
                                }
                                SocialProvider.GITHUB -> {
                                    +"Register an OAuth App in "
                                    a(
                                        href = "https://github.com/settings/developers",
                                        target = "_blank",
                                    ) { +"GitHub Developer Settings" }
                                    +". Set the callback URL to: "
                                    code {
                                        +"${workspace.issuerUrl ?: "https://your-domain.com"}/t/${workspace.slug}/auth/social/github/callback"
                                    }
                                }
                            }
                        }

                        if (isEditing || !isConfigured) {
                            // Show the inline edit form
                            form(
                                action = "/admin/workspaces/${workspace.slug}/settings/identity-providers/${prov.value}",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                            ) {
                                div("field") {
                                    label {
                                        htmlFor = "${prov.value}_clientId"
                                        +"Client ID"
                                    }
                                    input(type = InputType.text, name = "clientId") {
                                        id = "${prov.value}_clientId"
                                        placeholder = "Enter ${prov.displayName} client ID"
                                        required = true
                                        value = existing?.clientId ?: ""
                                        attributes["autocomplete"] = "off"
                                    }
                                }
                                div("field") {
                                    label {
                                        htmlFor = "${prov.value}_clientSecret"
                                        +"Client Secret"
                                    }
                                    input(type = InputType.password, name = "clientSecret") {
                                        id = "${prov.value}_clientSecret"
                                        attributes["autocomplete"] = "new-password"
                                        // Never pre-fill — secret is encrypted at rest
                                    }
                                    p("field-hint") {
                                        if (isConfigured) {
                                            +"A secret is already set. Leave blank to keep the existing secret."
                                        } else {
                                            +"Enter the OAuth2 client secret. It is stored encrypted."
                                        }
                                    }
                                }
                                div("checkbox-row") {
                                    input(type = InputType.checkBox, name = "enabled") {
                                        id = "${prov.value}_enabled"
                                        attributes["value"] = "true"
                                        if (existing?.enabled != false) checked = true
                                    }
                                    label("checkbox-label") {
                                        htmlFor = "${prov.value}_enabled"
                                        +"Enable ${prov.displayName} login"
                                    }
                                }
                                div {
                                    style = "display:flex; gap:0.75rem; margin-top:1.25rem;"
                                    button(type = ButtonType.submit, classes = "btn btn-sm") { +"Save" }
                                    if (isConfigured) {
                                        a(
                                            href = "/admin/workspaces/${workspace.slug}/settings/identity-providers",
                                            classes = "btn btn-ghost btn-sm",
                                        ) { +"Cancel" }
                                    }
                                }
                            }
                        } else {
                            // Collapsed view with edit + delete actions
                            div {
                                style = "display:flex; gap:0.75rem;"
                                a(
                                    href = "/admin/workspaces/${workspace.slug}/settings/identity-providers?edit=${prov.value}",
                                    classes = "btn btn-ghost btn-sm",
                                ) { +"Edit" }
                                form(
                                    action = "/admin/workspaces/${workspace.slug}/settings/identity-providers/${prov.value}/delete",
                                    method = FormMethod.post,
                                ) {
                                    button(type = ButtonType.submit, classes = "btn btn-ghost btn-sm btn-danger") {
                                        attributes["onclick"] =
                                            "return confirm('Remove ${prov.displayName} login configuration?')"
                                        +"Remove"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // API Keys settings (Phase 3a)
    // -------------------------------------------------------------------------

    fun apiKeysPage(
        workspace: Tenant,
        apiKeys: List<ApiKey>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        newKeyRaw: String? = null, // one-time plaintext shown after creation
        error: String? = null,
        scopes: List<String> = ApiScope.ALL,
    ): HTML.() -> Unit = {
        adminShell(
            pageTitle = "API Keys — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            loggedInAs = loggedInAs,
            activeAppSection = "api-keys",
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}/settings") { +"Settings" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"API Keys" }
            }
            div("page-header") {
                div {
                    p("page-title") { +"API Keys" }
                    p(
                        "page-subtitle",
                    ) { +"Machine-to-machine authentication for the REST API. Keys are shown once on creation." }
                }
            }

            // One-time key reveal
            if (newKeyRaw != null) {
                div("alert alert-success") {
                    style = "max-width:720px; margin-bottom:1.5rem;"
                    p("alert__title") { +"API key created — copy it now. You will not see it again." }
                    div("secret-box") { +newKeyRaw }
                }
            }

            if (error != null) {
                div("alert alert-error") {
                    style = "max-width:720px;"
                    +error
                }
            }

            // Existing keys table
            div("card") {
                style = "max-width:900px; margin-bottom:2rem;"
                if (apiKeys.isEmpty()) {
                    p("td-muted") {
                        style = "padding:1rem;"
                        +"No API keys yet. Create one below."
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Name" }
                                th { +"Prefix" }
                                th { +"Scopes" }
                                th { +"Last used" }
                                th { +"Expires" }
                                th { +"Status" }
                                th { +"" }
                            }
                        }
                        tbody {
                            apiKeys.forEach { key ->
                                tr {
                                    td { span("td-code") { +key.name } }
                                    td { span("td-code") { +"${key.keyPrefix}…" } }
                                    td {
                                        style = "font-size:0.78rem; color:var(--color-muted);"
                                        +key.scopes.joinToString(", ")
                                    }
                                    td {
                                        span("td-muted") {
                                            +(
                                                key.lastUsedAt?.let {
                                                    java.time.format.DateTimeFormatter
                                                        .ofPattern("MMM d, yyyy")
                                                        .withZone(java.time.ZoneId.of("UTC"))
                                                        .format(it)
                                                } ?: "Never"
                                            )
                                        }
                                    }
                                    td {
                                        span("td-muted") {
                                            +(
                                                key.expiresAt?.let {
                                                    java.time.format.DateTimeFormatter
                                                        .ofPattern("MMM d, yyyy")
                                                        .withZone(java.time.ZoneId.of("UTC"))
                                                        .format(it)
                                                } ?: "Never"
                                            )
                                        }
                                    }
                                    td {
                                        span(if (key.enabled) "badge badge-active" else "badge badge-disabled") {
                                            +(if (key.enabled) "Active" else "Revoked")
                                        }
                                    }
                                    td {
                                        if (key.enabled) {
                                            form(
                                                action = "/admin/workspaces/${workspace.slug}/settings/api-keys/${key.id}/revoke",
                                                method = FormMethod.post,
                                                classes = "inline-form",
                                            ) {
                                                button(
                                                    type = ButtonType.submit,
                                                    classes = "btn btn-ghost btn-sm btn-danger",
                                                ) {
                                                    attributes["onclick"] =
                                                        "return confirm('Revoke this API key? This cannot be undone.')"
                                                    +"Revoke"
                                                }
                                            }
                                        } else {
                                            form(
                                                action = "/admin/workspaces/${workspace.slug}/settings/api-keys/${key.id}/delete",
                                                method = FormMethod.post,
                                                classes = "inline-form",
                                            ) {
                                                button(type = ButtonType.submit, classes = "btn btn-ghost btn-sm") {
                                                    attributes["onclick"] = "return confirm('Delete this key?')"
                                                    +"Delete"
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

            // Create new key form
            div("form-card form-card--wide") {
                p("form-section-title") { +"Create New API Key" }
                form(
                    action = "/admin/workspaces/${workspace.slug}/settings/api-keys",
                    method = FormMethod.post,
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                ) {
                    div("field") {
                        label {
                            htmlFor = "keyName"
                            +"Name"
                        }
                        input(type = InputType.text, name = "name") {
                            id = "keyName"
                            placeholder = "e.g. CI/CD pipeline"
                            required = true
                            maxLength = "128"
                        }
                        p("field-hint") { +"A descriptive label to identify this key." }
                    }
                    div("field") {
                        label { +"Scopes" }
                        p("field-hint") {
                            style = "margin-bottom:0.5rem;"
                            +"Select the permissions this key will have."
                        }
                        div {
                            style = "display:grid; grid-template-columns:1fr 1fr; gap:0.5rem;"
                            ApiScope.ALL.forEach { scope ->
                                label {
                                    style =
                                        "display:flex; align-items:center; gap:0.5rem; font-size:0.875rem; font-weight:400;"
                                    input(type = InputType.checkBox, name = "scopes") {
                                        value = scope
                                        checked = true
                                    }
                                    span("td-code") {
                                        style = "font-size:0.8rem;"
                                        +scope
                                    }
                                }
                            }
                        }
                    }
                    div("field") {
                        label {
                            htmlFor = "expiresAt"
                            +"Expiry (optional)"
                        }
                        input(type = InputType.date, name = "expiresAt") {
                            id = "expiresAt"
                        }
                        p("field-hint") { +"Leave blank for keys that never expire." }
                    }
                    div("form-actions") {
                        button(type = ButtonType.submit, classes = "btn") { +"Create API Key" }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Webhooks page (Phase 4)
    // =========================================================================

    fun webhooksPage(
        workspace: Tenant,
        endpoints: List<WebhookEndpoint>,
        deliveries: List<WebhookDelivery>,
        allWorkspaces: List<Pair<String, String>>,
        loggedInAs: String,
        newSecret: String? = null,
        error: String? = null,
    ): HTML.() -> Unit =
        {
            adminShell(
                pageTitle = "Webhooks — ${workspace.displayName}",
                activeRail = "settings",
                allWorkspaces = allWorkspaces,
                workspaceName = workspace.displayName,
                workspaceSlug = workspace.slug,
                loggedInAs = loggedInAs,
                activeAppSection = "webhooks",
            ) {
                div("breadcrumb") {
                    a("/admin") { +"Workspaces" }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                    span("breadcrumb-sep") { +"/" }
                    a("/admin/workspaces/${workspace.slug}/settings") { +"Settings" }
                    span("breadcrumb-sep") { +"/" }
                    span("breadcrumb-current") { +"Webhooks" }
                }
                div("page-header") {
                    div {
                        p("page-title") { +"Webhooks" }
                        p(
                            "page-subtitle",
                        ) {
                            +"Receive HTTP callbacks when security events occur. Payloads are signed with HMAC-SHA256."
                        }
                    }
                }

                // One-time secret reveal
                if (newSecret != null) {
                    div("alert alert-success") {
                        style = "max-width:720px; margin-bottom:1.5rem;"
                        p("alert__title") { +"Webhook created — copy the signing secret now. You will not see it again." }
                        div("secret-box") { +newSecret }
                        p {
                            style = "margin-top:0.5rem; font-size:0.8rem; color:var(--color-muted);"
                            +"Verify incoming payloads: "
                            code { +"X-KotAuth-Signature: sha256=HMAC-SHA256(secret, body)" }
                        }
                    }
                }

                if (error != null) {
                    div("alert alert-error") {
                        style = "max-width:720px;"
                        +error
                    }
                }

                // ─── Endpoints table ───────────────────────────────────────────
                div("card") {
                    style = "max-width:960px; margin-bottom:2rem;"
                    if (endpoints.isEmpty()) {
                        p("td-muted") {
                            style = "padding:1rem;"
                            +"No webhook endpoints yet. Add one below."
                        }
                    } else {
                        table {
                            thead {
                                tr {
                                    th { +"URL" }
                                    th { +"Description" }
                                    th { +"Events" }
                                    th { +"Status" }
                                    th { +"Created" }
                                    th { +"" }
                                }
                            }
                            tbody {
                                endpoints.forEach { ep ->
                                    tr {
                                        td {
                                            style =
                                                "font-size:0.82rem; font-family:monospace; max-width:280px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"
                                            +ep.url
                                        }
                                        td {
                                            span("td-muted") { +(ep.description.ifBlank { "—" }) }
                                        }
                                        td {
                                            style = "font-size:0.78rem; color:var(--color-muted); max-width:200px;"
                                            +(
                                                ep.events
                                                    .sorted()
                                                    .joinToString(", ")
                                                    .ifBlank { "none" }
                                            )
                                        }
                                        td {
                                            span(if (ep.enabled) "badge badge-active" else "badge badge-disabled") {
                                                +(if (ep.enabled) "Enabled" else "Disabled")
                                            }
                                        }
                                        td {
                                            span("td-muted") {
                                                +DateTimeFormatter
                                                    .ofPattern("MMM d, yyyy")
                                                    .withZone(java.time.ZoneId.of("UTC"))
                                                    .format(ep.createdAt)
                                            }
                                        }
                                        td("btn-group") {
                                            // Toggle enable/disable
                                            form(
                                                action = "/admin/workspaces/${workspace.slug}/settings/webhooks/${ep.id}/toggle",
                                                method = FormMethod.post,
                                                classes = "inline-form",
                                            ) {
                                                input(type = InputType.hidden, name = "enabled") {
                                                    value = if (ep.enabled) "false" else "true"
                                                }
                                                button(type = ButtonType.submit, classes = "btn btn-ghost btn-sm") {
                                                    +(if (ep.enabled) "Disable" else "Enable")
                                                }
                                            }
                                            // Delete
                                            form(
                                                action = "/admin/workspaces/${workspace.slug}/settings/webhooks/${ep.id}/delete",
                                                method = FormMethod.post,
                                                classes = "inline-form",
                                            ) {
                                                button(
                                                    type = ButtonType.submit,
                                                    classes = "btn btn-ghost btn-sm btn-danger",
                                                ) {
                                                    attributes["onclick"] =
                                                        "return confirm('Delete this webhook endpoint? All delivery history will be lost.')"
                                                    +"Delete"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ─── Create endpoint form ──────────────────────────────────────
                div("form-card form-card--wide") {
                    style = "margin-bottom:2rem;"
                    p("form-section-title") { +"Add Webhook Endpoint" }
                    form(
                        action = "/admin/workspaces/${workspace.slug}/settings/webhooks",
                        method = FormMethod.post,
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                    ) {
                        div("field") {
                            label {
                                htmlFor = "whUrl"
                                +"Target URL"
                            }
                            input(type = InputType.url, name = "url") {
                                id = "whUrl"
                                placeholder = "https://your-app.example.com/webhooks/kotauth"
                                required = true
                                maxLength = "2048"
                            }
                            p("field-hint") { +"KotAuth will POST signed JSON payloads to this URL." }
                        }
                        div("field") {
                            label {
                                htmlFor = "whDesc"
                                +"Description (optional)"
                            }
                            input(type = InputType.text, name = "description") {
                                id = "whDesc"
                                placeholder = "e.g. Slack alerts integration"
                                maxLength = "256"
                            }
                        }
                        div("field") {
                            label { +"Events" }
                            p("field-hint") {
                                style = "margin-bottom:0.5rem;"
                                +"Select the events this endpoint should receive."
                            }
                            div {
                                style = "display:grid; grid-template-columns:1fr 1fr; gap:0.5rem;"
                                WebhookEvent.ALL.forEach { event ->
                                    label {
                                        style =
                                            "display:flex; align-items:center; gap:0.5rem; font-size:0.875rem; font-weight:400;"
                                        input(type = InputType.checkBox, name = "events") {
                                            value = event
                                            checked = true
                                        }
                                        span("td-code") {
                                            style = "font-size:0.8rem;"
                                            +event
                                        }
                                    }
                                }
                            }
                        }
                        div("form-actions") {
                            button(type = ButtonType.submit, classes = "btn") { +"Add Endpoint" }
                        }
                    }
                }

                // ─── Recent delivery history ───────────────────────────────────
                if (deliveries.isNotEmpty()) {
                    p("form-section-title") {
                        style = "max-width:960px;"
                        +"Recent Delivery History"
                    }
                    div("card") {
                        style = "max-width:960px;"
                        table {
                            thead {
                                tr {
                                    th { +"Event" }
                                    th { +"Endpoint" }
                                    th { +"Status" }
                                    th { +"HTTP" }
                                    th { +"Attempts" }
                                    th { +"Last attempt" }
                                }
                            }
                            tbody {
                                deliveries.take(50).forEach { d ->
                                    val ep = endpoints.firstOrNull { it.id == d.endpointId }
                                    tr {
                                        td {
                                            span("td-code") {
                                                style = "font-size:0.8rem;"
                                                +d.eventType
                                            }
                                        }
                                        td {
                                            style =
                                                "font-size:0.78rem; font-family:monospace; max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"
                                            +(ep?.url ?: "#${d.endpointId}")
                                        }
                                        td {
                                            span(
                                                when (d.status) {
                                                    WebhookDeliveryStatus.DELIVERED -> "badge badge-active"
                                                    WebhookDeliveryStatus.FAILED -> "badge badge-error"
                                                    WebhookDeliveryStatus.PENDING -> "badge badge-pending"
                                                },
                                            ) {
                                                +d.status.value
                                            }
                                        }
                                        td { span("td-muted") { +(d.responseStatus?.toString() ?: "—") } }
                                        td { span("td-muted") { +d.attempts.toString() } }
                                        td {
                                            span("td-muted") {
                                                +(
                                                    d.lastAttemptAt?.let {
                                                        DateTimeFormatter
                                                            .ofPattern("MMM d HH:mm")
                                                            .withZone(java.time.ZoneId.of("UTC"))
                                                            .format(it)
                                                    } ?: "—"
                                                )
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
    val themeLogoUrl: String = "",
)

/**
 * Holds create-application form values for prefill after a failed submission.
 */
data class ApplicationPrefill(
    val clientId: String = "",
    val name: String = "",
    val description: String = "",
    val accessType: String = "public",
    val redirectUris: String = "", // newline-separated URIs
)

/**
 * Holds create-user form values for prefill after a failed submission.
 */
data class UserPrefill(
    val username: String = "",
    val email: String = "",
    val fullName: String = "",
)
