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

internal fun apiKeysPageImpl(
    workspace: Tenant,
    apiKeys: List<ApiKey>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    newKeyRaw: String? = null,
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
