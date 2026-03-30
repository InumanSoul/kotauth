package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.JsIntegrity
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.Application
import com.kauth.domain.model.PortalLayout
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
                  contentClass = "content-outer",
) {
            div("content-inner") {
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
                                +workspace.slug
                            }
                        }
                    }
                }
                div("page-header__actions") {
                    ghostLinkExternal("/t/${workspace.slug}/authorize", "Open Login")
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

            // ── Applications ────────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Applications" }

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
                                            href =
                                                "/admin/workspaces/${workspace.slug}/applications/${app.clientId}",
                                            classes = "data-table__id",
                                        ) { +app.clientId }
                                    }
                                    td { span("data-table__name") { +app.name } }
                                    td {
                                        span("badge badge--public") {
                                            +app.accessType.label.uppercase()
                                        }
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
                                                href =
                                                    "/admin/workspaces/${workspace.slug}" +
                                                        "/applications/${app.clientId}",
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
                  contentClass = "content-outer",
) {
            div("content-inner content-inner--wide") {
            breadcrumb(
                "Workspaces" to "/admin",
                "New Workspace" to null,
            )

            // ── Page header with external submit ───────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Create Workspace" }
                        p("page-header__sub") {
                            +"A workspace is an isolated authorization boundary."
                        }
                    }
                }
                div("page-header__actions") {
                    a("/admin", classes = "btn btn--ghost") { +"Cancel" }
                    button(type = ButtonType.submit, classes = "btn btn--primary") {
                        attributes["form"] = "create-workspace-form"
                        +"Create Workspace"
                    }
                }
            }

            if (error != null) {
                div("notice notice--error") { +error }
            }

            // ── Identity card ──────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Identity" }
                form(
                    action = "/admin/workspaces",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    id = "create-workspace-form"

                    div("edit-row") {
                        span("edit-row__label") { +"Slug" }
                        div {
                            input(type = InputType.text, name = "slug") {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                this.id = "slug"
                                placeholder = "my-company"
                                value = prefill.slug
                                required = true
                                attributes["pattern"] = "[a-z0-9-]+"
                            }
                            div("edit-row__hint") {
                                +"Lowercase letters, numbers, hyphens. Used in token URLs: /t/my-company/…"
                            }
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Display Name" }
                        input(type = InputType.text, name = "displayName") {
                            classes = setOf("edit-row__field")
                            this.id = "displayName"
                            placeholder = "Acme Inc"
                            value = prefill.displayName
                            required = true
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Issuer URL (optional)" }
                        div {
                            input(type = InputType.url, name = "issuerUrl") {
                                classes = setOf("edit-row__field")
                                this.id = "issuerUrl"
                                placeholder = "https://auth.acme.com"
                                value = prefill.issuerUrl
                            }
                            div("edit-row__hint") {
                                +"The 'iss' claim in tokens. Defaults to /t/{slug} if blank."
                            }
                        }
                    }
                }
            }

            // ── Registration Policy card ───────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Registration Policy" }
                label("check-row") {
                    input(type = InputType.checkBox, name = "registrationEnabled") {
                        attributes["form"] = "create-workspace-form"
                        if (prefill.registrationEnabled) checked = true
                        attributes["value"] = "true"
                    }
                    div("check-row__body") {
                        span("check-row__label") { +"Allow public registration" }
                        span("check-row__desc") {
                            +"Anyone can create an account via the hosted login page."
                        }
                    }
                }
                label("check-row") {
                    input(type = InputType.checkBox, name = "emailVerificationRequired") {
                        attributes["form"] = "create-workspace-form"
                        if (prefill.emailVerificationRequired) checked = true
                        attributes["value"] = "true"
                    }
                    div("check-row__body") {
                        span("check-row__label") { +"Require email verification" }
                        span("check-row__desc") {
                            +"Users must confirm their email address before they can sign in."
                        }
                    }
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
        val slug = workspace.slug
        adminShell(
            pageTitle = "General Settings — ${workspace.displayName}",
            activeRail = "settings",
            activeAppSection = "general",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            loggedInAs = loggedInAs,
                    contentClass = "content-outer",
) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Settings" to "/admin/workspaces/$slug/settings",
                "General" to null,
            )

            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"General Settings" }
                        p("page-header__sub") {
                            +"Configure identity, token lifetimes and registration policy for ${workspace.displayName}."
                        }
                    }
                }
                div("page-header__actions") {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--primary")
                        attributes["form"] = "general-form"
                        +"Save Settings"
                    }
                }
            }

            if (saved) {
                div("notice notice--success") { +"Settings saved." }
            }
            if (error != null) {
                div("notice notice--error") { +error }
            }

            form(
                action = "/admin/workspaces/$slug/settings",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                id = "general-form"

                // ── Identity ───────────────────────────────────────
                div("ov-card") {
                    div("ov-card__section-label") { +"Identity" }
                    div("edit-row") {
                        span("edit-row__label") { +"Display Name" }
                        input(type = InputType.text, name = "displayName") {
                            classes = setOf("edit-row__field")
                            required = true
                            value = workspace.displayName
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Issuer URL" }
                        div {
                            input(type = InputType.url, name = "issuerUrl") {
                                classes = setOf("edit-row__field")
                                placeholder = "https://auth.example.com"
                                value = workspace.issuerUrl ?: ""
                            }
                            div("edit-row__hint") {
                                +"The "
                                code { +"iss" }
                                +" claim in tokens. Defaults to "
                                code { +"/t/$slug" }
                                +" if blank."
                            }
                        }
                    }
                }

                // ── Token Lifetimes ────────────────────────────────
                div("ov-card") {
                    div("ov-card__section-label") { +"Token Lifetimes" }
                    div("edit-row") {
                        span("edit-row__label") { +"Access Token TTL" }
                        div {
                            input(type = InputType.number, name = "tokenExpirySeconds") {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                required = true
                                attributes["min"] = "60"
                                value = workspace.tokenExpirySeconds.toString()
                            }
                            div("edit-row__hint") { +"Seconds. Minimum 60. Typical: 3600 (1h)." }
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Refresh Token TTL" }
                        div {
                            input(type = InputType.number, name = "refreshTokenExpirySeconds") {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                required = true
                                attributes["min"] = "60"
                                value = workspace.refreshTokenExpirySeconds.toString()
                            }
                            div("edit-row__hint") { +"Seconds. Must be ≥ access token TTL. Typical: 86400 (24h)." }
                        }
                    }
                }

                // ── Registration Policy ────────────────────────────
                div("ov-card") {
                    div("ov-card__section-label") { +"Registration Policy" }
                    label("check-row") {
                        input(type = InputType.checkBox, name = "registrationEnabled") {
                            attributes["value"] = "true"
                            if (workspace.registrationEnabled) checked = true
                        }
                        span("check-row__label") { +"Allow public registration" }
                    }
                    label("check-row") {
                        input(type = InputType.checkBox, name = "emailVerificationRequired") {
                            attributes["value"] = "true"
                            if (workspace.emailVerificationRequired) checked = true
                        }
                        span("check-row__label") { +"Require email verification" }
                    }
                }

                // ── Portal Layout ────────────────────────────────────
                div("ov-card") {
                    div("ov-card__section-label") { +"Self-Service Portal" }
                    div("edit-row") {
                        span("edit-row__label") { +"Layout" }
                        div {
                            select {
                                name = "portalLayout"
                                classes = setOf("edit-row__field")
                                for (layout in PortalLayout.entries) {
                                    option {
                                        value = layout.name
                                        if (workspace.portalConfig.layout == layout) selected = true
                                        +when (layout) {
                                            PortalLayout.SIDEBAR -> "Sidebar"
                                            PortalLayout.CENTERED -> "Centered Tabs"
                                        }
                                    }
                                }
                            }
                            div("edit-row__hint") {
                                +"Controls the navigation style of the user self-service portal."
                            }
                        }
                    }
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
        val slug = workspace.slug

        adminShell(
            pageTitle = "Security Policy — ${workspace.displayName}",
            activeRail = "settings",
            activeAppSection = "security",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            loggedInAs = loggedInAs,
                    contentClass = "content-outer",
) {
            div("content-inner") {
            // ── Breadcrumb ───────────────────────────────────────────
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Settings" to "/admin/workspaces/$slug/settings",
                "Security Policy" to null,
            )

            // ── Page header ──────────────────────────────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Security Policy" }
                        p("page-header__sub") {
                            +"Configure password rules and MFA requirements for ${workspace.displayName}."
                        }
                    }
                }
                div("page-header__actions") {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--primary")
                        attributes["form"] = "security-form"
                        +"Save Policy"
                    }
                }
            }

            // ── Notices ──────────────────────────────────────────────
            if (saved) {
                div("notice notice--success") { +"Security policy saved." }
            }
            if (error != null) {
                div("notice notice--error") { +error }
            }

            // ── Form (wraps both cards) ──────────────────────────────
            form(
                action = "/admin/workspaces/$slug/settings/security",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                id = "security-form"

                // ── Password Policy ──────────────────────────────────
                div("ov-card") {
                    div("ov-card__section-label") { +"Password Policy" }
                    div("edit-row") {
                        span("edit-row__label") { +"Minimum Length" }
                        div {
                            input(type = InputType.number, name = "passwordPolicyMinLength") {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                required = true
                                attributes["min"] = "4"
                                attributes["max"] = "128"
                                value = workspace.passwordPolicyMinLength.toString()
                            }
                            div("edit-row__hint") { +"Between 4 and 128 characters." }
                        }
                    }
                    label("check-row") {
                        input(type = InputType.checkBox, name = "passwordPolicyRequireSpecial") {
                            attributes["value"] = "true"
                            if (workspace.passwordPolicyRequireSpecial) checked = true
                        }
                        span("check-row__label") { +"Require special character" }
                        span("check-row__hint") { +"!@#\$%…" }
                    }
                    label("check-row") {
                        input(type = InputType.checkBox, name = "passwordPolicyRequireUppercase") {
                            attributes["value"] = "true"
                            if (workspace.passwordPolicyRequireUppercase) checked = true
                        }
                        span("check-row__label") { +"Require uppercase letter" }
                    }
                    label("check-row") {
                        input(type = InputType.checkBox, name = "passwordPolicyRequireNumber") {
                            attributes["value"] = "true"
                            if (workspace.passwordPolicyRequireNumber) checked = true
                        }
                        span("check-row__label") { +"Require at least one number" }
                    }
                    label("check-row") {
                        input(type = InputType.checkBox, name = "passwordPolicyBlacklistEnabled") {
                            attributes["value"] = "true"
                            if (workspace.passwordPolicyBlacklistEnabled) checked = true
                        }
                        span("check-row__label") { +"Block common / breached passwords" }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Password History" }
                        div {
                            input(type = InputType.number, name = "passwordPolicyHistoryCount") {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                attributes["min"] = "0"
                                attributes["max"] = "24"
                                value = workspace.passwordPolicyHistoryCount.toString()
                            }
                            div("edit-row__hint") { +"Previous passwords to remember. 0 = disabled, max 24." }
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Password Expiry" }
                        div {
                            input(type = InputType.number, name = "passwordPolicyMaxAgeDays") {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                attributes["min"] = "0"
                                attributes["max"] = "365"
                                value = workspace.passwordPolicyMaxAgeDays.toString()
                            }
                            div("edit-row__hint") { +"Days before forced change. 0 = never expires." }
                        }
                    }
                }

                // ── Multi-Factor Authentication ──────────────────────
                div("ov-card") {
                    div("ov-card__section-label") { +"Multi-Factor Authentication" }
                    div("radio-group") {
                        label("radio-row") {
                            input(type = InputType.radio, name = "mfaPolicy") {
                                value = "optional"
                                if (workspace.mfaPolicy == "optional") checked = true
                            }
                            div("radio-row__body") {
                                span("radio-row__label") { +"Optional" }
                                span("radio-row__desc") {
                                    +"Users may enroll in TOTP-based MFA but are not required to."
                                }
                            }
                        }
                        label("radio-row") {
                            input(type = InputType.radio, name = "mfaPolicy") {
                                value = "required"
                                if (workspace.mfaPolicy == "required") checked = true
                            }
                            div("radio-row__body") {
                                span("radio-row__label") { +"Required" }
                                span("radio-row__desc") {
                                    +"Users must enroll in MFA before they can access applications."
                                }
                            }
                        }
                        label("radio-row") {
                            input(type = InputType.radio, name = "mfaPolicy") {
                                value = "required_admins"
                                if (workspace.mfaPolicy == "required_admins") checked = true
                            }
                            div("radio-row__body") {
                                span("radio-row__label") { +"Required for admins only" }
                                span("radio-row__desc") {
                                    +"Only admin users must enroll. Regular users may opt in."
                                }
                            }
                        }
                    }
                }

                // ── Account Lockout ──────────────────────────────────
                div("ov-card") {
                    div("ov-card__section-label") { +"Account Lockout" }
                    div("edit-row") {
                        span("edit-row__label") { +"Max failed attempts" }
                        div {
                            input(type = InputType.number, name = "lockoutMaxAttempts") {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                attributes["min"] = "0"
                                attributes["max"] = "100"
                                value = workspace.securityConfig.lockoutMaxAttempts.toString()
                            }
                            div("edit-row__hint") {
                                +"Set to 0 to disable lockout. Maximum 100."
                            }
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Lockout duration" }
                        div {
                            input(type = InputType.number, name = "lockoutDurationMinutes") {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                attributes["min"] = "1"
                                attributes["max"] = "1440"
                                value = workspace.securityConfig.lockoutDurationMinutes.toString()
                            }
                            div("edit-row__hint") {
                                +"Minutes before the account automatically unlocks. "
                                +"Only applies when Max failed attempts is greater than 0."
                            }
                        }
                    }
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
                  contentClass = "content-outer",
) {
            div("content-inner content-inner--wide") {
            val t = workspace.theme
            val slug = workspace.slug

            // ── Breadcrumb ─────────────────────────────────────────
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Settings" to "/admin/workspaces/$slug/settings",
                "Branding" to null,
            )

            // ── Page header ────────────────────────────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Branding" }
                        p("page-header__sub") {
                            +"Customize the appearance of ${workspace.displayName}'s auth pages."
                        }
                    }
                }
                div("page-header__actions") {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--primary")
                        attributes["form"] = "branding-form"
                        +"Save Branding"
                    }
                }
            }

            // ── Alerts ─────────────────────────────────────────────
            if (saved) {
                div("notice notice--success") { +"Branding saved." }
            }
            if (error != null) {
                div("notice notice--error") { +error }
            }

            // ── Two-column layout ──────────────────────────────────
            form(
                action = "/admin/workspaces/$slug/settings/branding",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                id = "branding-form"

                div("branding-layout") {

                    // ══════════════════════════════════════════════
                    //  LEFT COLUMN — form sections
                    // ══════════════════════════════════════════════
                    div("branding-form") {

                        // ── Assets ─────────────────────────────────
                        div("ov-card") {
                            div("ov-card__section-label") { +"Assets" }
                            div("edit-row") {
                                span("edit-row__label") { +"Logo URL" }
                                div {
                                    input(type = InputType.url, name = "themeLogoUrl") {
                                        classes = setOf("edit-row__field")
                                        id = "field-logo"
                                        placeholder = "https://cdn.example.com/logo.png"
                                        value = t.logoUrl ?: ""
                                        attributes["data-logo-preview"] = ""
                                    }
                                    div("edit-row__hint") {
                                        +"Shown above the login card. Recommended max 180×48px."
                                    }
                                }
                            }
                            div("edit-row") {
                                span("edit-row__label") { +"Favicon URL" }
                                div {
                                    input(type = InputType.url, name = "themeFaviconUrl") {
                                        classes = setOf("edit-row__field")
                                        id = "field-favicon"
                                        placeholder = "https://cdn.example.com/favicon.ico"
                                        value = t.faviconUrl ?: ""
                                    }
                                    div("edit-row__hint") {
                                        +"Browser tab icon. Recommended 32×32px .ico or .png."
                                    }
                                }
                            }
                        }

                        // ── Theme Presets ──────────────────────────
                        div("ov-card") {
                            div("ov-card__section-label") { +"Theme Preset" }
                            div("preset-group") {
                                button(type = ButtonType.button) {
                                    classes = setOf("preset-btn", "preset-btn--active")
                                    attributes["data-preset"] = "dark"
                                    +"Dark"
                                }
                                button(type = ButtonType.button) {
                                    classes = setOf("preset-btn")
                                    attributes["data-preset"] = "light"
                                    +"Light"
                                }
                                button(type = ButtonType.button) {
                                    classes = setOf("preset-btn")
                                    attributes["data-preset"] = "simple"
                                    +"Simple"
                                }
                            }
                        }

                        // ── Colors ─────────────────────────────────
                        div("ov-card") {
                            div("ov-card__section-label") { +"Colors" }
                            div("color-grid") {
                                colorField("Accent", "accent", "themeAccentColor", t.accentColor)
                                colorField("Accent Hover", "accent-hover", "themeAccentHover", t.accentHoverColor)
                                colorField("Accent Text", "accent-fg", "themeAccentForeground", t.accentForeground)
                                colorField("Page Background", "page-bg", "themeBgDeep", t.bgDeep)
                                colorField("Surface", "surface", "themeSurface", t.surface)
                                colorField("Input Background", "input-bg", "themeBgInput", t.bgInput)
                                colorField("Border", "border", "themeBorderColor", t.borderColor)
                                colorField("Text Primary", "text", "themeTextPrimary", t.textPrimary)
                                colorField("Text Muted", "muted", "themeTextMuted", t.textMuted)
                            }
                        }

                        // ── Border Radius ──────────────────────────
                        div("ov-card") {
                            div("ov-card__section-label") { +"Border Radius" }
                            div("preset-group") {
                                val radiusPresets = listOf("0px" to "Sharp", "8px" to "Rounded", "40px" to "Pill")
                                for ((rv, label) in radiusPresets) {
                                    button(type = ButtonType.button) {
                                        classes = if (t.borderRadius == rv) {
                                            setOf("preset-btn", "preset-btn--active")
                                        } else {
                                            setOf("preset-btn")
                                        }
                                        attributes["data-radius"] = rv
                                        +label
                                    }
                                }
                            }
                            input(type = InputType.text, name = "themeBorderRadius") {
                                classes = setOf("edit-row__field")
                                id = "field-radius"
                                value = t.borderRadius
                                placeholder = "e.g. 0px, 6px, 12px"
                                attributes["data-radius-input"] = ""
                            }
                        }

                        // ── Font Family ───────────────────────────
                        div("ov-card") {
                            div("ov-card__section-label") { +"Font Family" }
                            val fontOptions = listOf(
                                "Inter",
                                "Montserrat",
                                "IBM Plex Sans",
                                "DM Sans",
                                "Source Sans 3",
                            )
                            select {
                                name = "themeFontFamily"
                                classes = setOf("edit-row__field")
                                for (f in fontOptions) {
                                    option {
                                        value = f
                                        if (t.fontFamily == f) selected = true
                                        +f
                                    }
                                }
                            }
                        }
                    }

                    // ══════════════════════════════════════════════
                    //  RIGHT COLUMN — sticky live preview
                    // ══════════════════════════════════════════════
                    div("branding-preview") {
                        div("preview-panel") {
                            div("preview-panel__header") {
                                +"Preview"
                                span("preview-panel__label") { +"Live — updates as you edit" }
                            }
                            div("preview-panel__body") {
                                id = "preview-body"
                                style = "background:${t.bgDeep};"

                                div("auth-mock") {
                                    div("auth-mock__card") {
                                        id = "preview-card"
                                        style = "--pm-accent:${t.accentColor};--pm-accent-fg:${t.accentForeground};--pm-card:${t.surface};--pm-input:${t.bgInput};--pm-border:${t.borderColor};--pm-text:${t.textPrimary};--pm-muted:${t.textMuted};--pm-radius:${t.borderRadius};"

                                        div("auth-mock__logo-area") {
                                            div("auth-mock__logo-placeholder") {
                                                id = "preview-logo-placeholder"
                                                +"LOGO"
                                            }
                                            div("auth-mock__title") { +"Sign in" }
                                            div("auth-mock__subtitle") {
                                                +"to continue to ${workspace.displayName}"
                                            }
                                        }
                                        div("auth-mock__field") {
                                            style = "border-radius:${t.borderRadius};"
                                            +"Email or username"
                                        }
                                        div("auth-mock__field") {
                                            style = "border-radius:${t.borderRadius};"
                                            +"Password"
                                        }
                                        div("auth-mock__btn") {
                                            id = "preview-btn"
                                            style = "background:${t.accentColor};color:${t.accentForeground};border-radius:${t.borderRadius};"
                                            +"Sign in"
                                        }
                                        div("auth-mock__footer") {
                                            +"No account? "
                                            a("#") { +"Register" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Script: live preview + presets (external) ──────────
            script(src = "/static/js/branding.min.js?v=${AppInfo.assetVersion}") {
                attributes["defer"] = "true"
                JsIntegrity.branding?.let { attributes["integrity"] = it }
                attributes["crossorigin"] = "anonymous"
            }
                    }
}
    }

// ── Color field helper ─────────────────────────────────────────────────
// Renders one cell in the .color-grid: label + swatch/native picker + hex input.
// `key` is the short preview key (e.g. "accent"), `formName` is the form field name.
private fun DIV.colorField(label: String, key: String, formName: String, currentValue: String) {
    div("color-field") {
        div("color-field__label") { +label }
        div("color-field__picker") {
            div("color-field__swatch-wrap") {
                div("color-field__swatch") {
                    id = "swatch-$key"
                    style = "background:$currentValue;"
                }
                input(type = InputType.color) {
                    classes = setOf("color-field__native")
                    id = "native-$key"
                    name = formName
                    value = currentValue
                    attributes["data-color-key"] = key
                }
            }
            input(type = InputType.text) {
                classes = setOf("color-field__hex")
                id = "hex-$key"
                value = currentValue.uppercase()
                maxLength = "7"
                attributes["data-hex-key"] = key
            }
        }
    }
}

