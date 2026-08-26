package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.DEFAULT_OIDC_SCOPES
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import kotlinx.html.*

internal fun mfaSettingsPageImpl(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
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
        val isRequired = workspace.mfaPolicy == "required" || workspace.mfaPolicy == "required_admins"
        val enrollmentRate = if (totalUsers > 0) "${enrolledUsers * 100 / totalUsers}%" else "—"
        val enrollUrl = "/t/${workspace.slug}/account/mfa/enroll"
        val securityPolicyUrl = "/admin/workspaces/${workspace.slug}/settings/security"

        adminShell(
            pageTitle = "MFA — ${workspace.displayName}",
            activeRail = "security",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "mfa",
            loggedInAs = loggedInAs,
            contentClass = "content-outer",
        ) {
            div("content-inner") {
                // Breadcrumb
                breadcrumb(
                    "Workspaces" to "/admin",
                    workspace.slug to "/admin/workspaces/${workspace.slug}",
                    "Security" to "/admin/workspaces/${workspace.slug}/settings/security",
                    "MFA" to null,
                )

                pageHeader(
                    title = "Multi-Factor Authentication",
                    subtitleContent = {
                        +"TOTP-based MFA enrollment status for ${workspace.displayName}. Policy: "
                        val badgeMod =
                            when (workspace.mfaPolicy) {
                                "required" -> "badge--active"
                                "required_admins" -> "badge--info"
                                else -> "badge--inactive"
                            }
                        span("badge $badgeMod") { +policyLabel }
                    },
                    actions = {
                        a(securityPolicyUrl, classes = "btn btn--ghost") {
                            inlineSvgIcon("lock", "Security")
                            +"Security Policy"
                        }
                    },
                )

                // 4-state conditional alert — suppressed when at least one user is enrolled
                when {
                    isRequired && enrolledUsers == 0 -> notice(
                        title = EnglishStrings.ADMIN_MFA_ALERT_NO_ENROLLED_TITLE,
                        description = "${EnglishStrings.ADMIN_MFA_ALERT_REQUIRED_DESC_PREFIX} \"$policyLabel\"" +
                            EnglishStrings.ADMIN_MFA_ALERT_REQUIRED_DESC_SUFFIX,
                        linkHref = securityPolicyUrl,
                        linkText = EnglishStrings.ADMIN_MFA_ALERT_REQUIRED_LINK,
                    )
                    !isRequired && enrolledUsers == 0 -> notice(
                        title = EnglishStrings.ADMIN_MFA_ALERT_NO_ENROLLED_TITLE,
                        description = EnglishStrings.ADMIN_MFA_ALERT_OPTIONAL_DESC,
                        modifier = "notice--info",
                        iconName = "info",
                        linkHref = securityPolicyUrl,
                        linkText = EnglishStrings.ADMIN_MFA_ALERT_OPTIONAL_LINK,
                    )
                }

                // 2-column insight bar — policy cell moved to page subtitle
                div("insight-bar insight-bar--cols-2") {
                    div("insight-item insight-item--static") {
                        span("insight-item__label") { +"Enrolled Users" }
                        span("insight-item__value insight-item__value--mono") {
                            +"$enrolledUsers"
                            span("insight-item__denominator") { +" / $totalUsers" }
                        }
                        span("insight-item__hint") { +"$enrollmentRate enrollment rate" }
                    }

                    div("insight-item insight-item--static") {
                        span("insight-item__label") { +"Not Enrolled" }
                        val notEnrolledValueClass = when {
                            notEnrolled == 0 -> "insight-item__value insight-item__value--ok"
                            isRequired -> "insight-item__value insight-item__value--warn"
                            else -> "insight-item__value"
                        }
                        span(notEnrolledValueClass) { +"$notEnrolled" }
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

                // Merged user table — enrolled first (secondary: username alphabetical)
                ovCard {
                    ovSectionLabel("Users")
                    val allUsers = (enrolledUserList + notEnrolledUserList)
                        .sortedWith(compareBy({ !it.mfaEnabled }, { it.username }))
                    if (allUsers.isEmpty()) {
                        emptyState(
                            iconName = "user",
                            title = EnglishStrings.ADMIN_MFA_EMPTY_USERS_TITLE,
                            description = EnglishStrings.ADMIN_MFA_EMPTY_USERS_DESC,
                        )
                    } else {
                        table("data-table") {
                            thead {
                                tr {
                                    th { +EnglishStrings.ADMIN_MFA_TABLE_COL_USERNAME }
                                    th { +EnglishStrings.ADMIN_MFA_TABLE_COL_FULL_NAME }
                                    th { +EnglishStrings.ADMIN_MFA_TABLE_COL_EMAIL }
                                    th { +EnglishStrings.ADMIN_MFA_TABLE_COL_STATUS }
                                }
                            }
                            tbody {
                                allUsers.forEach { u ->
                                    tr {
                                        td {
                                            a(
                                                "/admin/workspaces/${workspace.slug}/users/${u.id?.value}",
                                                classes = "data-table__id",
                                            ) { +u.username }
                                        }
                                        td { span("data-table__name") { +u.fullName } }
                                        td { span("data-table__email") { +u.email } }
                                        td {
                                            if (u.mfaEnabled) {
                                                span("badge badge--active") {
                                                    +EnglishStrings.ADMIN_MFA_TABLE_BADGE_ENROLLED
                                                }
                                            } else {
                                                span("badge badge--inactive") {
                                                    +EnglishStrings.ADMIN_MFA_TABLE_BADGE_NOT_ENROLLED
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
 * Displays the Identity Providers configuration page for a tenant.
 * Shows a list of supported providers (Google, GitHub) with their current
 * configuration status and a form to add/update each provider.
 */
internal fun identityProvidersPageImpl(
    workspace: Tenant,
    providers: List<IdentityProvider>,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    error: String? = null,
    saved: Boolean = false,
    failures: Map<ProviderKey, List<SignInFailureRow>> = emptyMap(),
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
            workspaceLogoUrl = workspace.theme.logoUrl,
            loggedInAs = loggedInAs,
            activeAppSection = "identity-providers",
                  contentClass = "content-outer",
            toastMessage = if (saved) EnglishStrings.TOAST_IDP_SAVED else null,
) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Settings" to "/admin/workspaces/$slug/settings",
                "Identity Providers" to null,
            )

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
            if (error != null) {
                div("notice notice--error") { +error }
            }

            // ── Provider cards ───────────────────────────────────────
            val providerMap = providers.associateBy { it.provider }

            for (prov in ProviderKey.RESERVED) {
                val existing = providerMap[prov]
                val isConfigured = existing != null
                val providerName = EnglishStrings.providerDisplayName(prov)
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
                                +providerName
                                if (isConfigured) {
                                    val badgeCls = if (existing.enabled) "badge badge--active" else "badge badge--inactive"
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
                                // A provider key is open, so no exhaustive branch exists; the
                                // fallback is unreachable while this loop walks RESERVED only.
                                when (prov) {
                                    ProviderKey.GOOGLE -> {
                                        +"Create credentials in "
                                        a(
                                            href = "https://console.cloud.google.com/apis/credentials",
                                            target = "_blank",
                                        ) { +"Google Cloud Console" }
                                        +". Set the authorized redirect URI to:"
                                    }
                                    ProviderKey.GITHUB -> {
                                        +"Register an OAuth App in "
                                        a(
                                            href = "https://github.com/settings/developers",
                                            target = "_blank",
                                        ) { +"GitHub Developer Settings" }
                                        +". Set the callback URL to:"
                                    }
                                    else -> {
                                        +"Register this workspace with $providerName. Set the callback URL to:"
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
                            span("edit-row__label") { +EnglishStrings.IDP_CLIENT_ID_LABEL }
                            input(type = InputType.text, name = "clientId") {
                                classes = setOf("edit-row__field")
                                placeholder = "Enter $providerName client ID"
                                required = true
                                value = existing?.clientId ?: ""
                                attributes["autocomplete"] = "off"
                            }
                        }
                        div("edit-row") {
                            span("edit-row__label") { +EnglishStrings.IDP_CLIENT_SECRET_LABEL }
                            div {
                                input(type = InputType.password, name = "clientSecret") {
                                    classes = setOf("edit-row__field")
                                    placeholder = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
                                    attributes["autocomplete"] = "new-password"
                                }
                                div("edit-row__hint") {
                                    if (isConfigured) {
                                        +EnglishStrings.IDP_SECRET_STORED_HINT
                                    } else {
                                        +EnglishStrings.IDP_SECRET_NEW_HINT
                                    }
                                }
                            }
                        }

                        // ── Save action ──────────────────────────────
                        div("edit-actions") {
                            button(type = ButtonType.submit) {
                                classes = setOf("btn", "btn--primary", "btn--sm")
                                +"Save $providerName"
                            }
                        }
                    }

                    identityProviderFailuresPanel(failures[prov].orEmpty())
                }
            }

            // ── OIDC providers ───────────────────────────────────────
            h2 { +EnglishStrings.IDP_OIDC_SECTION_TITLE }
            p("page-header__sub") { +EnglishStrings.IDP_OIDC_SECTION_SUB }

            val oidcProviders =
                providers
                    .filter { it.provider !in ProviderKey.RESERVED }
                    .sortedBy { it.provider.value }

            if (oidcProviders.isEmpty()) {
                div("ov-card") { p { +EnglishStrings.IDP_NO_OIDC_PROVIDERS } }
            }
            for (existing in oidcProviders) {
                oidcProviderCard(
                    slug = slug,
                    baseUrl = baseUrl,
                    existing = existing,
                    failures = failures[existing.provider].orEmpty(),
                )
            }
            oidcProviderCard(slug = slug, baseUrl = baseUrl, existing = null)
                    }
}
    }

/**
 * One OIDC provider — the edit form for a configured row, or the add form when [existing] is null.
 *
 * The client secret is a write-only field on both surfaces: this form posts one and never
 * renders one back, so a configured row shows an empty box and a hint that blank keeps what
 * is stored.
 */
private fun FlowContent.oidcProviderCard(
    slug: String,
    baseUrl: String,
    existing: IdentityProvider?,
    failures: List<SignInFailureRow> = emptyList(),
) {
    val key = existing?.provider?.value
    val action =
        if (key == null) {
            "/admin/workspaces/$slug/settings/identity-providers"
        } else {
            "/admin/workspaces/$slug/settings/identity-providers/$key"
        }

    div("ov-card") {
        form(
            action = action,
            encType = FormEncType.applicationXWwwFormUrlEncoded,
            method = FormMethod.post,
        ) {
            val heading =
                if (existing == null) {
                    EnglishStrings.IDP_ADD_TITLE
                } else {
                    existing.displayName ?: EnglishStrings.providerDisplayName(existing.provider)
                }
            div("provider-header") {
                div("provider-header__name") {
                    +heading
                    if (existing != null) {
                        val badgeCls = if (existing.enabled) "badge badge--active" else "badge badge--inactive"
                        span(badgeCls) { +(if (existing.enabled) "Enabled" else "Disabled") }
                    }
                }
                label("toggle") {
                    input(type = InputType.checkBox, name = "enabled") {
                        attributes["value"] = "true"
                        if (existing == null || existing.enabled) checked = true
                    }
                    span("toggle__track") { span("toggle__thumb") {} }
                    span("toggle__label toggle__label--muted") { +EnglishStrings.IDP_ENABLE_LABEL }
                }
            }

            if (existing == null) {
                div("edit-row") {
                    span("edit-row__label") { +EnglishStrings.IDP_KEY_LABEL }
                    div {
                        input(type = InputType.text, name = "providerKey") {
                            classes = setOf("edit-row__field")
                            placeholder = "okta"
                            required = true
                            attributes["autocomplete"] = "off"
                        }
                        div("edit-row__hint") { +EnglishStrings.IDP_KEY_HINT }
                    }
                }
            } else {
                val callbackUrl = "$baseUrl/t/$slug/auth/social/$key/callback"
                div("setup-row") {
                    div("setup-row__text") { +EnglishStrings.IDP_CALLBACK_HINT }
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
            }

            div("edit-row") {
                span("edit-row__label") { +EnglishStrings.IDP_KIND_LABEL }
                div {
                    select {
                        classes = setOf("edit-row__field")
                        name = "kind"
                        for (kind in ProviderKind.entries) {
                            option {
                                value = kind.value
                                if ((existing?.kind ?: ProviderKind.OIDC) == kind) selected = true
                                +when (kind) {
                                    ProviderKind.OIDC -> EnglishStrings.IDP_KIND_OIDC
                                    ProviderKind.OAUTH2 -> EnglishStrings.IDP_KIND_OAUTH2
                                }
                            }
                        }
                    }
                    div("edit-row__hint") { +EnglishStrings.IDP_KIND_HINT }
                }
            }

            idpTextRow(
                label = EnglishStrings.IDP_DISPLAY_NAME_LABEL,
                fieldName = "displayName",
                value = existing?.displayName,
                hint = EnglishStrings.IDP_DISPLAY_NAME_HINT,
                placeholder = "Okta",
            )
            idpTextRow(
                label = EnglishStrings.IDP_ISSUER_LABEL,
                fieldName = "issuer",
                value = existing?.issuer,
                hint = EnglishStrings.IDP_ISSUER_HINT,
                placeholder = "https://example.okta.com",
            )
            idpTextRow(
                label = EnglishStrings.IDP_CLIENT_ID_LABEL,
                fieldName = "clientId",
                value = existing?.clientId,
                hint = null,
                placeholder = EnglishStrings.IDP_CLIENT_ID_PLACEHOLDER,
                required = true,
            )

            div("edit-row") {
                span("edit-row__label") { +EnglishStrings.IDP_CLIENT_SECRET_LABEL }
                div {
                    input(type = InputType.password, name = "clientSecret") {
                        classes = setOf("edit-row__field")
                        placeholder = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
                        attributes["autocomplete"] = "new-password"
                    }
                    div("edit-row__hint") {
                        if (existing == null) {
                            +EnglishStrings.IDP_SECRET_NEW_HINT
                        } else {
                            +EnglishStrings.IDP_SECRET_STORED_HINT
                        }
                    }
                }
            }

            idpTextRow(
                label = EnglishStrings.IDP_SCOPES_LABEL,
                fieldName = "scopes",
                value = existing?.scopes ?: DEFAULT_OIDC_SCOPES,
                hint = EnglishStrings.IDP_SCOPES_HINT,
                placeholder = DEFAULT_OIDC_SCOPES,
            )
            idpTextRow(
                label = EnglishStrings.IDP_AUTHORIZATION_ENDPOINT_LABEL,
                fieldName = "authorizationEndpoint",
                value = existing?.authorizationEndpoint,
                hint = EnglishStrings.IDP_ENDPOINT_OVERRIDE_HINT,
                placeholder = "",
            )
            idpTextRow(
                label = EnglishStrings.IDP_TOKEN_ENDPOINT_LABEL,
                fieldName = "tokenEndpoint",
                value = existing?.tokenEndpoint,
                hint = EnglishStrings.IDP_ENDPOINT_OVERRIDE_HINT,
                placeholder = "",
            )
            idpTextRow(
                label = EnglishStrings.IDP_JWKS_URI_LABEL,
                fieldName = "jwksUri",
                value = existing?.jwksUri,
                hint = EnglishStrings.IDP_ENDPOINT_OVERRIDE_HINT,
                placeholder = "",
            )

            div("edit-actions") {
                button(type = ButtonType.submit) {
                    classes = setOf("btn", "btn--primary", "btn--sm")
                    +(if (existing == null) EnglishStrings.IDP_ADD_BUTTON else EnglishStrings.IDP_SAVE_BUTTON)
                }
            }
        }

        if (existing != null) {
            identityProviderFailuresPanel(failures)
        }

        // A separate form: the delete POST must not carry the edit form's fields, and HTML
        // forbids nesting one inside the other.
        if (key != null) {
            form(
                action = "/admin/workspaces/$slug/settings/identity-providers/$key/delete",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                div("edit-actions") {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--danger", "btn--sm")
                        +EnglishStrings.IDP_DELETE_BUTTON
                    }
                }
            }
        }
    }
}

private fun FlowContent.idpTextRow(
    label: String,
    fieldName: String,
    value: String?,
    hint: String?,
    placeholder: String,
    required: Boolean = false,
) {
    div("edit-row") {
        span("edit-row__label") { +label }
        div {
            input(type = InputType.text, name = fieldName) {
                classes = setOf("edit-row__field")
                this.placeholder = placeholder
                this.required = required
                this.value = value ?: ""
                attributes["autocomplete"] = "off"
            }
            if (hint != null) {
                div("edit-row__hint") { +hint }
            }
        }
    }
}

// ─── API Keys ───────────────────────────────────────────────────────────────

internal fun apiKeysListPageImpl(
    workspace: Tenant,
    apiKeys: List<ApiKey>,
    allWorkspaces: List<WorkspaceStub>,
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
        workspaceLogoUrl = workspace.theme.logoUrl,
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
                            td {
                                span("key-table__name") { +key.name }
                                if (key.bootstrapName != null) {
                                    span("badge badge--neutral badge--inline") { +"Bootstrapped" }
                                }
                            }
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
                                when {
                                    key.bootstrapName != null -> {
                                        val bootstrapHint =
                                            "Managed via KAUTH_BOOTSTRAP_API_KEYS \u2014 edit the env var to rotate or revoke."
                                        span("key-table__meta key-table__meta--with-icon") {
                                            attributes["aria-label"] = bootstrapHint
                                            +"Env-managed "
                                            span("inline-help") {
                                                title = bootstrapHint
                                                inlineSvgIcon("info", "Bootstrap key information")
                                            }
                                        }
                                    }
                                    key.enabled ->
                                        form(
                                            action = "/admin/workspaces/$slug/settings/api-keys/${key.id}/revoke",
                                            method = FormMethod.post,
                                        ) {
                                            button(type = ButtonType.submit) {
                                                classes = setOf("btn", "btn--ghost", "btn--sm", "btn--danger")
                                                attributes["data-confirm"] =
                                                    "Revoke this API key? This cannot be undone."
                                                +"Revoke"
                                            }
                                        }
                                    else ->
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
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    error: String? = null,
    scopes: List<String> = ApiScope.ALL,
    preselectedScopes: Set<String> = emptySet(),
    selectedDialect: String = com.kauth.domain.model.ApiKey.DEFAULT_SCIM_DIALECT,
): HTML.() -> Unit = {
    val slug = workspace.slug
    val totalScopes = scopes.size

    adminShell(
        pageTitle = "New API Key — ${workspace.displayName}",
        activeRail = "settings",
        allWorkspaces = allWorkspaces,
        workspaceName = workspace.displayName,
        workspaceSlug = slug,
        workspaceLogoUrl = workspace.theme.logoUrl,
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
                                checked = scope in preselectedScopes
                                attributes["form"] = "create-api-key-form"
                            }
                            span("scope-chip__label") { +scope }
                        }
                    }
                }
            }
        }

        // ── Provisioning dialect ─────────────────────────────────
        div("ov-card") {
            ovSectionLabel(com.kauth.adapter.web.EnglishStrings.SCIM_DIALECT_FIELD_LABEL)
            scimDialectSelector(selectedDialect)
        }
                }
}
}
