package com.kauth.adapter.web.admin

import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.Application
import com.kauth.domain.model.Tenant
import kotlinx.html.*

/**
 * Workspace-related admin views.
 *
 * Contains the workspace overview/detail page (new design) and all
 * workspace settings pages (migrated as-is from AdminView).
 *
 * Workspace Detail (new design) — shows insight bar, notice banner, and applications table.
 *
 * This replaces the old workspaceDetailPage() with the new design from
 * kotauth-workspace-overview.html.
 */
internal fun workspaceDetailPageImpl(
    workspace: Tenant,
    allWorkspaces: List<Pair<String, String>>,
    apps: List<Application> = emptyList(),
    loggedInAs: String,
): HTML.() -> Unit =
    {
        val appPairs = apps.map { it.clientId to it.name }
        adminShell(
            pageTitle = workspace.displayName,
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            apps = appPairs,
            loggedInAs = loggedInAs,
        ) {
            breadcrumb(
                "Workspaces" to "/admin",
                workspace.slug to null,
            )

            // ── Page header with workspace avatar ────────────────────
            div("page-header") {
                div("page-header__left") {
                    // Square workspace avatar with edit overlay
                    div("ws-avatar") {
                        +(
                            workspace.displayName
                                .firstOrNull()
                                ?.uppercaseChar()
                                ?.toString()
                                ?: "W"
                            )
                        a(
                            href = "/admin/workspaces/${workspace.slug}/settings/branding",
                            classes = "ws-avatar__edit",
                        ) {
                            attributes["title"] = "Edit branding"
                            inlineSvgIcon("edit", "edit")
                        }
                    }
                    div("page-header__identity") {
                        h1("page-header__title") { +workspace.displayName }
                        div("page-header__meta") {
                            span("page-header__slug") {
                                inlineSvgIcon("slug", "slug")
                                +workspace.slug
                            }
                        }
                    }
                }
                div("page-header__actions") {
                    ghostLinkExternal("/t/${workspace.slug}/login", "Open Login")
                    ghostLinkExternal("/t/${workspace.slug}/account/login", "Open Portal")
                    primaryLink(
                        "/admin/workspaces/${workspace.slug}/applications/new",
                        "New Application",
                        "plus",
                    )
                }
            }

            // ── Notice banner (conditional: SMTP not configured) ─────
            if (!workspace.isSmtpReady) {
                notice(
                    title = "SMTP not configured",
                    description = "Email delivery is disabled. " +
                        "Users cannot receive verification or password reset emails.",
                    linkHref = "/admin/workspaces/${workspace.slug}/settings/smtp",
                    linkText = "Configure SMTP",
                )
            }

            // ── Insight bar ──────────────────────────────────────────
            div("insight-bar") {
                // Registration
                a(
                    href = "/admin/workspaces/${workspace.slug}/settings/security",
                    classes = "insight-item",
                ) {
                    span("insight-item__label") { +"Registration" }
                    if (workspace.registrationEnabled) {
                        span("insight-item__value insight-item__value--ok") { +"Open" }
                    } else {
                        span("insight-item__value insight-item__value--warn") { +"Closed" }
                    }
                    span("insight-item__hint") {
                        +"Email verification ${if (workspace.emailVerificationRequired) "on" else "off"}"
                    }
                    span("insight-item__arrow") {
                        +"Security policy"
                        inlineSvgIcon("arrow-small", "arrow")
                    }
                }

                // Token TTL
                a(
                    href = "/admin/workspaces/${workspace.slug}/settings/security",
                    classes = "insight-item",
                ) {
                    span("insight-item__label") { +"Token TTL" }
                    span("insight-item__value insight-item__value--mono") {
                        +formatTtl(workspace.tokenExpirySeconds)
                        +" / "
                        +formatTtl(workspace.refreshTokenExpirySeconds)
                    }
                    span("insight-item__hint") { +"Access · Refresh" }
                    span("insight-item__arrow") {
                        +"Security policy"
                        inlineSvgIcon("arrow-small", "arrow")
                    }
                }

                // Identity Providers
                a(
                    href = "/admin/workspaces/${workspace.slug}/settings/identity-providers",
                    classes = "insight-item",
                ) {
                    span("insight-item__label") { +"Identity Providers" }
                    span("insight-item__value insight-item__value--muted") { +"None" }
                    span("insight-item__hint") { +"Password auth only" }
                    span("insight-item__arrow") {
                        +"Add provider"
                        inlineSvgIcon("arrow-small", "arrow")
                    }
                }

                // API Keys
                a(
                    href = "/admin/workspaces/${workspace.slug}/settings/api-keys",
                    classes = "insight-item",
                ) {
                    span("insight-item__label") { +"API Keys" }
                    span("insight-item__value") { +"—" }
                    span("insight-item__hint") { +"Keys issued · 0 webhooks" }
                    span("insight-item__arrow") {
                        +"Manage"
                        inlineSvgIcon("arrow-small", "arrow")
                    }
                }
            }

            // ── Applications table ───────────────────────────────────
            div("section__header") {
                div {
                    div("section__title") { +"Applications" }
                    div("section__desc") { +"OAuth2 / OIDC clients registered in this workspace" }
                }
            }

            if (apps.isEmpty()) {
                emptyState(
                    iconName = "redirect",
                    title = "No applications yet",
                    description = "Register your first OAuth2 / OIDC client to get started.",
                ) {
                    a(
                        href = "/admin/workspaces/${workspace.slug}/applications/new",
                        classes = "empty-state__cta",
                    ) { +"+ New Application" }
                }
            } else {
                table("data-table") {
                    thead {
                        tr {
                            th {
                                style = "width:210px;"
                                +"Client ID"
                            }
                            th { +"Name" }
                            th {
                                style = "width:110px;"
                                +"Type"
                            }
                            th {
                                style = "width:110px;"
                                +"Status"
                            }
                            th { style = "width:70px;" }
                        }
                    }
                    tbody {
                        apps.forEach { app ->
                            tr {
                                td {
                                    a(
                                        href = "/admin/workspaces/${workspace.slug}/applications/${app.clientId}",
                                        classes = "data-table__id",
                                    ) { +app.clientId }
                                }
                                td { span("data-table__name") { +app.name } }
                                td {
                                    span("badge badge--public") { +app.accessType.label.uppercase() }
                                }
                                td {
                                    if (app.enabled) {
                                        span("badge badge--active") {
                                            span("badge__dot") {}
                                            +"ACTIVE"
                                        }
                                    } else {
                                        span("badge badge--inactive") {
                                            span("badge__dot") {}
                                            +"DISABLED"
                                        }
                                    }
                                }
                                td {
                                    div("data-table__actions") {
                                        a(
                                            href = "/admin/workspaces/${workspace.slug}/applications/${app.clientId}",
                                            classes = "btn btn--ghost btn--sm",
                                        ) {
                                            +"Open"
                                            inlineSvgIcon("open-sm", "open")
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
 * TTL Formatting
 */
private fun formatTtl(seconds: Long): String =
    when {
        seconds >= 86400 && seconds % 86400 == 0L -> "${seconds / 86400}d"
        seconds >= 3600 && seconds % 3600 == 0L -> "${seconds / 3600}h"
        seconds >= 60 && seconds % 60 == 0L -> "${seconds / 60}m"
        else -> "${seconds}s"
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

// Create workspace form.
internal fun createWorkspacePageImpl(
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

// Workspace settings form.
internal fun workspaceSettingsPageImpl(
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

// Security policy page.
internal fun securityPolicyPageImpl(
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

// Branding page.
internal fun brandingPageImpl(
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
