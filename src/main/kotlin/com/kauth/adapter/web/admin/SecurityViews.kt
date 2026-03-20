package com.kauth.adapter.web.admin

import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.model.Tenant
import kotlinx.html.*

internal fun mfaSettingsPageImpl(
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

/**
 * Displays the Identity Providers configuration page for a tenant.
 * Shows a list of supported providers (Google, GitHub) with their current
 * configuration status and a form to add/update each provider.
 */
internal fun identityProvidersPageImpl(
    workspace: Tenant,
    providers: List<IdentityProvider>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    editProvider: SocialProvider? = null,
    error: String? = null,
    saved: Boolean = false,
): HTML.() -> Unit =
    {
        val slug = workspace.slug
        val baseUrl = workspace.issuerUrl ?: "https://your-domain.com"

        adminShell(
            pageTitle = "Identity Providers — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            loggedInAs = loggedInAs,
            activeAppSection = "identity-providers",
        ) {
            // ── Breadcrumb ───────────────────────────────────────────
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Settings" to "/admin/workspaces/$slug/settings",
                "Identity Providers" to null,
            )

            // ── Page header ──────────────────────────────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Identity Providers" }
                        p("page-header__sub") {
                            +"Configure SSO. Users can sign in with their existing accounts."
                        }
                    }
                }
            }

            // ── Notices ──────────────────────────────────────────────
            if (saved) {
                div("notice notice--success") { +"Identity provider settings saved." }
            }
            if (error != null) {
                div("notice notice--error") { +error }
            }

            // ── Provider cards ───────────────────────────────────────
            val providerMap = providers.associateBy { it.provider }

            for (prov in SocialProvider.entries) {
                val existing = providerMap[prov]
                val isConfigured = existing != null
                val callbackUrl = "$baseUrl/t/$slug/auth/social/${prov.value}/callback"

                div("ov-card") {
                    form(
                        action = "/admin/workspaces/$slug/settings/identity-providers/${prov.value}",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        // ── Header: name + badge + toggle ────────────
                        div("provider-header") {
                            div("provider-header__name") {
                                +prov.displayName
                                if (isConfigured) {
                                    val badgeCls = if (existing!!.enabled) "badge badge--active" else "badge badge--inactive"
                                    span(badgeCls) { +(if (existing.enabled) "Enabled" else "Disabled") }
                                } else {
                                    span("badge badge--inactive") { +"Not configured" }
                                }
                            }
                            label("toggle") {
                                input(type = InputType.checkBox, name = "enabled") {
                                    attributes["value"] = "true"
                                    if (existing?.enabled != false) checked = true
                                }
                                span("toggle__track") { span("toggle__thumb") {} }
                                span("toggle__label toggle__label--muted") { +"Enable" }
                            }
                        }

                        // ── Setup instructions + callback URL ────────
                        div("setup-row") {
                            div("setup-row__text") {
                                when (prov) {
                                    SocialProvider.GOOGLE -> {
                                        +"Create credentials in "
                                        a(
                                            href = "https://console.cloud.google.com/apis/credentials",
                                            target = "_blank",
                                        ) { +"Google Cloud Console" }
                                        +". Set the authorized redirect URI to:"
                                    }
                                    SocialProvider.GITHUB -> {
                                        +"Register an OAuth App in "
                                        a(
                                            href = "https://github.com/settings/developers",
                                            target = "_blank",
                                        ) { +"GitHub Developer Settings" }
                                        +". Set the callback URL to:"
                                    }
                                }
                            }
                            div("copy-field") {
                                span("copy-field__value") { +callbackUrl }
                                button(type = ButtonType.button) {
                                    classes = setOf("copy-field__btn")
                                    attributes["data-copy"] = callbackUrl
                                    title = "Copy"
                                    +"\u2398"
                                }
                            }
                        }

                        // ── Credentials ──────────────────────────────
                        div("edit-row") {
                            span("edit-row__label") { +"Client ID" }
                            input(type = InputType.text, name = "clientId") {
                                classes = setOf("edit-row__field")
                                placeholder = "Enter ${prov.displayName} client ID"
                                required = true
                                value = existing?.clientId ?: ""
                                attributes["autocomplete"] = "off"
                            }
                        }
                        div("edit-row") {
                            span("edit-row__label") { +"Client Secret" }
                            div {
                                input(type = InputType.password, name = "clientSecret") {
                                    classes = setOf("edit-row__field")
                                    placeholder = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
                                    attributes["autocomplete"] = "new-password"
                                }
                                div("edit-row__hint") {
                                    if (isConfigured) {
                                        +"Stored encrypted. Leave blank to keep existing secret."
                                    } else {
                                        +"Stored encrypted."
                                    }
                                }
                            }
                        }

                        // ── Save action ──────────────────────────────
                        div("edit-actions") {
                            button(type = ButtonType.submit) {
                                classes = setOf("btn", "btn--primary", "btn--sm")
                                +"Save ${prov.displayName}"
                            }
                        }
                    }
                }
            }
        }
    }

internal fun apiKeysPageImpl(
    workspace: Tenant,
    apiKeys: List<ApiKey>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    newKeyRaw: String? = null,
    error: String? = null,
    scopes: List<String> = ApiScope.ALL,
): HTML.() -> Unit = {
    val slug = workspace.slug
    val totalScopes = scopes.size

    adminShell(
        pageTitle = "API Keys — ${workspace.displayName}",
        activeRail = "settings",
        allWorkspaces = allWorkspaces,
        workspaceName = workspace.displayName,
        workspaceSlug = slug,
        loggedInAs = loggedInAs,
        activeAppSection = "api-keys",
    ) {
        // ── Breadcrumb ───────────────────────────────────────────
        breadcrumb(
            "Workspaces" to "/admin",
            slug to "/admin/workspaces/$slug",
            "Settings" to "/admin/workspaces/$slug/settings",
            "API Keys" to null,
        )

        // ── Page header ──────────────────────────────────────────
        div("page-header") {
            div("page-header__left") {
                div("page-header__identity") {
                    h1("page-header__title") { +"API Keys" }
                    p("page-header__sub") {
                        +"Machine-to-machine authentication. Keys are shown once on creation."
                    }
                }
            }
        }

        // ── One-time key reveal ──────────────────────────────────
        if (newKeyRaw != null) {
            div("notice notice--success") {
                p { +"API key created — copy it now. You will not see it again." }
                div("copy-field") {
                    span("copy-field__value") { +newKeyRaw }
                    button(type = ButtonType.button) {
                        classes = setOf("copy-field__btn")
                        attributes["data-copy"] = newKeyRaw
                        title = "Copy"
                        +"\u2398"
                    }
                }
            }
        }

        if (error != null) {
            div("notice notice--error") { +error }
        }

        // ── Existing keys table ──────────────────────────────────
        if (apiKeys.isEmpty()) {
            div("empty-state") {
                p("empty-state__title") { +"No API keys yet" }
                p("empty-state__desc") { +"Create a key below to enable machine-to-machine access." }
            }
        } else {
            table("key-table") {
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
                            td { span("key-table__name") { +key.name } }
                            td { span("key-table__meta") { +"${key.keyPrefix}\u2026" } }
                            td {
                                span("key-table__meta") { +key.scopes.joinToString(", ") }
                            }
                            td {
                                span("key-table__meta") {
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
                                span("key-table__meta") {
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
                                val badgeCls = if (key.enabled) "badge badge--active" else "badge badge--inactive"
                                span(badgeCls) { +(if (key.enabled) "Active" else "Revoked") }
                            }
                            td {
                                if (key.enabled) {
                                    form(
                                        action = "/admin/workspaces/$slug/settings/api-keys/${key.id}/revoke",
                                        method = FormMethod.post,
                                    ) {
                                        button(type = ButtonType.submit) {
                                            classes = setOf("btn", "btn--ghost", "btn--sm", "btn--danger")
                                            attributes["data-confirm"] = "Revoke this API key? This cannot be undone."
                                            +"Revoke"
                                        }
                                    }
                                } else {
                                    form(
                                        action = "/admin/workspaces/$slug/settings/api-keys/${key.id}/delete",
                                        method = FormMethod.post,
                                    ) {
                                        button(type = ButtonType.submit) {
                                            classes = setOf("btn", "btn--ghost", "btn--sm")
                                            attributes["data-confirm"] = "Delete this key?"
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

        // ── Create new key form ──────────────────────────────────
        div("ov-card") {
            div("ov-card__section-label") { +"Create New API Key" }
            form(
                action = "/admin/workspaces/$slug/settings/api-keys",
                method = FormMethod.post,
                encType = FormEncType.applicationXWwwFormUrlEncoded,
            ) {
                div("edit-row") {
                    span("edit-row__label") { +"Name" }
                    div {
                        input(type = InputType.text, name = "name") {
                            classes = setOf("edit-row__field")
                            placeholder = "e.g. CI/CD pipeline"
                            required = true
                            maxLength = "128"
                        }
                        div("edit-row__hint") { +"A descriptive label to identify this key." }
                    }
                }

                // ── Scopes chip grid ─────────────────────────────
                div {
                    div("chip-grid__header") {
                        span("chip-grid__header-label") { +"Scopes" }
                        div("chip-grid__header-actions") {
                            span("chip-grid__count") {
                                id = "scopes-count"
                                +"0 / $totalScopes selected"
                            }
                            button(type = ButtonType.button) {
                                classes = setOf("chip-grid__toggle")
                                attributes["data-chips-all"] = "scopes-grid"
                                +"All"
                            }
                            button(type = ButtonType.button) {
                                classes = setOf("chip-grid__toggle")
                                attributes["data-chips-none"] = "scopes-grid"
                                +"None"
                            }
                        }
                    }
                    div("chip-grid") {
                        id = "scopes-grid"
                        scopes.forEach { scope ->
                            label("scope-chip") {
                                input(type = InputType.checkBox, name = "scopes") {
                                    value = scope
                                }
                                span("scope-chip__label") { +scope }
                            }
                        }
                    }
                }

                div("edit-row") {
                    span("edit-row__label") { +"Expiry" }
                    div {
                        input(type = InputType.date, name = "expiresAt") {
                            classes = setOf("edit-row__field")
                        }
                        div("edit-row__hint") { +"Leave blank for keys that never expire." }
                    }
                }

                div("edit-actions") {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--primary")
                        +"Create API Key"
                    }
                }
            }
        }
    }
}
