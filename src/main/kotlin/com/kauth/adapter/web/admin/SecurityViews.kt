package com.kauth.adapter.web.admin

import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import kotlinx.html.*

internal fun mfaSettingsPageImpl(
    workspace: Tenant,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    totalUsers: Int = 0,
    enrolledUsers: Int = 0,
    enrolledUserList: List<User> = emptyList(),
    notEnrolledUserList: List<User> = emptyList(),
): HTML.() -> Unit =
    {
        val notEnrolled = totalUsers - enrolledUsers
        val policyLabel =
            when (workspace.mfaPolicy) {
                "required" -> "Required"
                "required_admins" -> "Required (admins)"
                else -> "Optional"
            }
        val enrollmentRate = if (totalUsers > 0) "${enrolledUsers * 100 / totalUsers}%" else "—"
        val enrollUrl = "/t/${workspace.slug}/account/mfa/enroll"

        adminShell(
            pageTitle = "MFA — ${workspace.displayName}",
            activeRail = "security",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "mfa",
            loggedInAs = loggedInAs,
            contentClass = "content-outer",
        ) {
            div("content-inner") {
                // Breadcrumb
                breadcrumb(
                    "Workspaces" to "/admin",
                    workspace.slug to "/admin/workspaces/${workspace.slug}",
                    "Security" to "/admin/workspaces/${workspace.slug}/sessions",
                    "MFA" to null,
                )

                // Page header
                pageHeader(
                    title = "Multi-Factor Authentication",
                    subtitle = "TOTP-based MFA enrollment status for ${workspace.displayName}.",
                    actions = {
                        a(
                            "/admin/workspaces/${workspace.slug}/settings/security",
                            classes = "btn btn--ghost",
                        ) {
                            inlineSvgIcon("lock", "Security")
                            +"Security Policy"
                        }
                    },
                )

                // Notice: conditional — shown when no users enrolled and policy is optional
                if (enrolledUsers == 0 && workspace.mfaPolicy == "optional") {
                    notice(
                        title = "No users have enrolled in MFA",
                        description = "Policy is set to Optional. Share the enrollment URL below to encourage users to enable MFA.",
                        linkHref = "/admin/workspaces/${workspace.slug}/settings/security",
                        linkText = "Change policy",
                    )
                }

                // Stat strip — 3-column insight bar
                div("insight-bar insight-bar--cols-3") {
                    // Current Policy
                    a(
                        href = "/admin/workspaces/${workspace.slug}/settings/security",
                        classes = "insight-item",
                    ) {
                        span("insight-item__label") { +"Current Policy" }
                        span("insight-item__value") {
                            val badgeMod =
                                when (workspace.mfaPolicy) {
                                    "required" -> "badge--active"
                                    "required_admins" -> "badge--info"
                                    else -> "badge--inactive"
                                }
                            span("badge $badgeMod") { +policyLabel }
                        }
                        span("insight-item__arrow") {
                            +"Change in Security Policy"
                            inlineSvgIcon("arrow-small", "arrow")
                        }
                    }

                    // Enrolled Users
                    div("insight-item insight-item--static") {
                        span("insight-item__label") { +"Enrolled Users" }
                        span("insight-item__value insight-item__value--mono") {
                            +"$enrolledUsers"
                            span {
                                attributes["style"] =
                                    "font-size:14px;color:var(--color-subtle);font-family:var(--font-sans);font-weight:400;"
                                +" / $totalUsers"
                            }
                        }
                        span("insight-item__hint") { +"$enrollmentRate enrollment rate" }
                    }

                    // Not Enrolled
                    div("insight-item insight-item--static") {
                        span("insight-item__label") { +"Not Enrolled" }
                        val valueClass =
                            if (notEnrolled > 0) {
                                "insight-item__value insight-item__value--warn"
                            } else {
                                "insight-item__value insight-item__value--ok"
                            }
                        span(valueClass) { +"$notEnrolled" }
                        span("insight-item__hint") {
                            +if (notEnrolled == 1) "user without MFA" else "users without MFA"
                        }
                    }
                }

                // Enrollment URL card
                ovCard {
                    ovSectionLabel("Self-service enrollment URL")
                    div("info-card-body") {
                        p("info-card-body__desc") {
                            +"Share this URL with users to let them enroll their TOTP authenticator app. "
                            +"The link requires them to be signed in."
                        }
                        div("copy-field") {
                            span("copy-field__value") { +enrollUrl }
                            button(classes = "copy-field__btn") {
                                type = ButtonType.button
                                attributes["data-copy"] = enrollUrl
                                attributes["title"] = "Copy"
                                inlineSvgIcon("copy", "Copy")
                            }
                        }
                    }
                }

                // Enrolled users table
                ovCard {
                    ovSectionLabel("Enrolled Users")
                    if (enrolledUserList.isEmpty()) {
                        emptyState(
                            iconName = "rail-security",
                            title = "No users enrolled yet",
                            description = "Users who enable MFA will appear here with their enrollment date.",
                        )
                    } else {
                        table("data-table") {
                            thead {
                                tr {
                                    th { +"Username" }
                                    th { +"Full Name" }
                                    th { +"Email" }
                                }
                            }
                            tbody {
                                enrolledUserList.forEach { u ->
                                    tr {
                                        td {
                                            a(
                                                "/admin/workspaces/${workspace.slug}/users/${u.id}",
                                                classes = "mfa-user-id",
                                            ) { +u.username }
                                        }
                                        td { span("mfa-user-name") { +u.fullName } }
                                        td { span("mfa-user-email") { +u.email } }
                                    }
                                }
                            }
                        }
                    }
                }

                // Not enrolled table
                ovCard {
                    ovSectionLabel("Not Enrolled")
                    if (notEnrolledUserList.isEmpty() && notEnrolled == 0) {
                        emptyState(
                            iconName = "check-circle",
                            title = "All users enrolled",
                            description = "Every user in this workspace has MFA enabled.",
                        )
                    } else if (notEnrolledUserList.isEmpty()) {
                        // User lists not yet wired from backend — show count-based placeholder
                        emptyState(
                            iconName = "user",
                            title = "$notEnrolled user${if (notEnrolled != 1) "s" else ""} without MFA",
                            description = "Connect the user data to see individual enrollment status.",
                        )
                    } else {
                        table("data-table") {
                            thead {
                                tr {
                                    th { +"Username" }
                                    th { +"Full Name" }
                                    th { +"Email" }
                                }
                            }
                            tbody {
                                notEnrolledUserList.forEach { u ->
                                    tr {
                                        td {
                                            a(
                                                "/admin/workspaces/${workspace.slug}/users/${u.id}",
                                                classes = "mfa-user-id",
                                            ) { +u.username }
                                        }
                                        td { span("mfa-user-name") { +u.fullName } }
                                        td { span("mfa-user-email") { +u.email } }
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
 * Displays the Identity Providers configuration page for a tenant.
 * Shows a list of supported providers (Google, GitHub) with their current
 * configuration status and a form to add/update each provider.
 */
internal fun identityProvidersPageImpl(
    workspace: Tenant,
    providers: List<IdentityProvider>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
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
                  contentClass = "content-outer",
) {
            div("content-inner") {
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
                                    if (existing?.enabled == true) checked = true
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
                                    inlineSvgIcon("copy", "Copy")
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
    }

// ─── API Keys ───────────────────────────────────────────────────────────────

internal fun apiKeysListPageImpl(
    workspace: Tenant,
    apiKeys: List<ApiKey>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    newKeyRaw: String? = null,
    error: String? = null,
): HTML.() -> Unit = {
    val slug = workspace.slug

    adminShell(
        pageTitle = "API Keys — ${workspace.displayName}",
        activeRail = "settings",
        allWorkspaces = allWorkspaces,
        workspaceName = workspace.displayName,
        workspaceSlug = slug,
        loggedInAs = loggedInAs,
        activeAppSection = "api-keys",
    contentClass = "content-outer",
) {
            div("content-inner") {
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
            div("page-header__actions") {
                primaryLink(
                    "/admin/workspaces/$slug/settings/api-keys/new",
                    "New API Key",
                    "plus",
                )
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
                        inlineSvgIcon("copy", "Copy")
                    }
                }
            }
        }

        if (error != null) {
            div("notice notice--error") { +error }
        }

        // ── Keys table / empty state ─────────────────────────────
        if (apiKeys.isEmpty() && newKeyRaw == null) {
            emptyState(
                iconName = "code",
                title = "No API keys yet",
                description = "Create a key to enable machine-to-machine access to this workspace.",
                cta = {
                    a(
                        href = "/admin/workspaces/$slug/settings/api-keys/new",
                        classes = "empty-state__cta",
                    ) {
                        inlineSvgIcon("plus", "New")
                        +"Create API Key"
                    }
                },
            )
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
                }
}
}

internal fun createApiKeyPageImpl(
    workspace: Tenant,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    error: String? = null,
    scopes: List<String> = ApiScope.ALL,
): HTML.() -> Unit = {
    val slug = workspace.slug
    val totalScopes = scopes.size

    adminShell(
        pageTitle = "New API Key — ${workspace.displayName}",
        activeRail = "settings",
        allWorkspaces = allWorkspaces,
        workspaceName = workspace.displayName,
        workspaceSlug = slug,
        loggedInAs = loggedInAs,
        activeAppSection = "api-keys",
    contentClass = "content-outer",
) {
            div("content-inner") {
        // ── Breadcrumb ───────────────────────────────────────────
        breadcrumb(
            "Workspaces" to "/admin",
            slug to "/admin/workspaces/$slug",
            "Settings" to "/admin/workspaces/$slug/settings",
            "API Keys" to "/admin/workspaces/$slug/settings/api-keys",
            "New API Key" to null,
        )

        // ── Page header ──────────────────────────────────────────
        div("page-header") {
            div("page-header__left") {
                div("page-header__identity") {
                    h1("page-header__title") { +"Create API Key" }
                    p("page-header__sub") {
                        +"The key value is shown once after creation. Store it securely."
                    }
                }
            }
            div("page-header__actions") {
                button(type = ButtonType.submit, classes = "btn btn--primary") {
                    attributes["form"] = "create-api-key-form"
                    +"Create API Key"
                }
            }
        }

        if (error != null) {
            div("notice notice--error") { +error }
        }

        // ── Form ─────────────────────────────────────────────────
        div("ov-card") {
            div("ov-card__section-label") { +"Key Details" }
            form(
                action = "/admin/workspaces/$slug/settings/api-keys",
                method = FormMethod.post,
                encType = FormEncType.applicationXWwwFormUrlEncoded,
            ) {
                id = "create-api-key-form"

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

                div("edit-row") {
                    span("edit-row__label") { +"Expiry" }
                    div {
                        input(type = InputType.date, name = "expiresAt") {
                            classes = setOf("edit-row__field")
                        }
                        div("edit-row__hint") { +"Leave blank for keys that never expire." }
                    }
                }
            }
        }

        // ── Scopes card ──────────────────────────────────────────
        div("ov-card") {
            div("ov-card__section-label") { +"Scopes" }
            div {
                div("chip-grid__header") {
                    span("chip-grid__header-label") { +"Select permissions for this key" }
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
                                attributes["form"] = "create-api-key-form"
                            }
                            span("scope-chip__label") { +scope }
                        }
                    }
                }
            }
        }
                }
}
}
