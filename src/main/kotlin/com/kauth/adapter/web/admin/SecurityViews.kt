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
import com.kauth.domain.model.socialCallbackUrl
import com.kauth.domain.model.socialCallbackUrlTemplate
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.DiscoveryProbe
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
            pageTitle = "MFA · ${workspace.displayName}",
            activeRail = "security",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "mfa",
            loggedInAs = loggedInAs,
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
 * Identity Providers index — a table of what is configured, and a grid of what can be added.
 *
 * Configuration lives on a per-provider route rather than expanded inline. The save idiom here
 * is POST-redirect-GET, which resets any `<details>` to collapsed and scrolls to the top, so an
 * inline disclosure would collapse itself on every save. The columns carry the signals that make
 * the catalog safe to scan — a recent-failure count above all, since a catalog that hides a
 * broken provider is worse than the long form it replaces.
 */
internal fun identityProvidersIndexPageImpl(
    workspace: Tenant,
    providers: List<IdentityProvider>,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    error: String? = null,
    saved: Boolean = false,
    deleted: Boolean = false,
    failures: Map<ProviderKey, List<SignInFailureRow>> = emptyMap(),
): HTML.() -> Unit =
    {
        val slug = workspace.slug
        val base = "/admin/workspaces/$slug/settings/identity-providers"

        adminShell(
            pageTitle = "Identity Providers · ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            loggedInAs = loggedInAs,
            activeAppSection = "identity-providers",
            toastMessage =
                when {
                    saved -> EnglishStrings.TOAST_IDP_SAVED
                    deleted -> EnglishStrings.TOAST_IDP_DELETED
                    else -> null
                },
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    slug to "/admin/workspaces/$slug",
                    "Settings" to "/admin/workspaces/$slug/settings",
                    EnglishStrings.IDP_PAGE_TITLE to null,
                )

                div("page-header") {
                    div("page-header__left") {
                        div("page-header__identity") {
                            h1("page-header__title") { +EnglishStrings.IDP_PAGE_TITLE }
                            p("page-header__sub") { +EnglishStrings.IDP_PAGE_SUB }
                        }
                    }
                }

                if (error != null) errorNotice(error)

                val configured = providers.sortedBy { it.provider.value }

                div("ov-card") {
                    div("ov-card__section-label") { +EnglishStrings.IDP_CONFIGURED_HEADING }
                    if (configured.isEmpty()) {
                        // Deliberately no call to action: the catalog below is the one.
                        emptyState(
                            iconName = "globe",
                            title = EnglishStrings.IDP_NONE_TITLE,
                            description = EnglishStrings.IDP_NONE_DESC,
                        )
                    } else {
                        table("data-table") {
                            thead {
                                tr {
                                    th { +EnglishStrings.IDP_COL_PROVIDER }
                                    th { +EnglishStrings.IDP_COL_ISSUER }
                                    th { +EnglishStrings.IDP_COL_JIT }
                                    th { +EnglishStrings.IDP_COL_FAILURES }
                                    th { +EnglishStrings.IDP_COL_STATUS }
                                    th { +"" }
                                }
                            }
                            tbody {
                                configured.forEach { provider ->
                                    providerRow(base, provider, failures[provider.provider].orEmpty())
                                }
                            }
                        }
                    }
                }

                div("ov-card") {
                    div("ov-card__section-label") { +EnglishStrings.IDP_ADD_HEADING }
                    div("provider-grid") {
                        // The reserved keys are a fixed set, so their tiles go straight to the
                        // provider's own page. Everything else needs a key named first.
                        providerTile(
                            href = "$base/${ProviderKey.GOOGLE.value}",
                            iconName = "google-logo",
                            name = EnglishStrings.providerDisplayName(ProviderKey.GOOGLE),
                            hint = EnglishStrings.IDP_TILE_OAUTH2_HINT,
                        )
                        providerTile(
                            href = "$base/${ProviderKey.GITHUB.value}",
                            iconName = "github-logo",
                            name = EnglishStrings.providerDisplayName(ProviderKey.GITHUB),
                            hint = EnglishStrings.IDP_TILE_OAUTH2_HINT,
                        )
                        providerTile(
                            href = "$base/new",
                            // The same mark the sign-in page shows for every brokered provider.
                            iconName = "globe",
                            name = EnglishStrings.IDP_KIND_OIDC,
                            hint = EnglishStrings.IDP_TILE_OIDC_HINT,
                        )
                    }
                }
            }
        }
    }

/** One row of the configured-providers table. */
private fun TBODY.providerRow(
    base: String,
    provider: IdentityProvider,
    failures: List<SignInFailureRow>,
) {
    val href = "$base/${provider.provider.value}"
    tr {
        td {
            a(href, classes = "data-table__name") {
                +(provider.displayName?.takeIf { it.isNotBlank() } ?: EnglishStrings.providerDisplayName(provider.provider))
            }
        }
        td {
            // The one field that says which IdP this actually is; a display name is
            // operator-chosen and can read as anything.
            val issuerHost = provider.issuer?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: it }
            if (issuerHost != null) {
                span("data-table__meta") { +issuerHost }
            } else {
                span("data-table__meta") { +EnglishStrings.IDP_BUILT_IN }
            }
        }
        td {
            span("data-table__meta") {
                if (provider.jitEnabled) {
                    +EnglishStrings.jitOnWithDomains(provider.jitAllowedDomains.size)
                } else {
                    +EnglishStrings.IDP_JIT_OFF
                }
            }
        }
        td {
            if (failures.isEmpty()) {
                span("data-table__meta") { +"—" }
            } else {
                span("badge badge--danger") { +EnglishStrings.recentFailures(failures.size) }
            }
        }
        td { providerStatusBadge(provider) }
        td("data-table__actions") {
            a(href, classes = "btn btn--ghost btn--sm") { +EnglishStrings.IDP_CONFIGURE }
        }
    }
}

/**
 * Three states, not two: a provider that exists but is switched off is not the same as one that
 * was never set up, and reading them as one grey badge hid the difference.
 */
private fun FlowContent.providerStatusBadge(provider: IdentityProvider?) {
    when {
        provider == null -> span("badge badge--inactive") { +EnglishStrings.IDP_STATUS_NOT_CONFIGURED }
        provider.enabled -> span("badge badge--active") { +EnglishStrings.IDP_STATUS_ENABLED }
        else -> span("badge badge--warn") { +EnglishStrings.IDP_STATUS_DISABLED }
    }
}

/** A whole-tile link in the "add a provider" grid. */
private fun FlowContent.providerTile(
    href: String,
    iconName: String,
    name: String,
    hint: String,
) {
    a(href, classes = "provider-tile") {
        span("provider-tile__logo") { inlineSvgIcon(iconName, name) }
        span("provider-tile__name") { +name }
        span("provider-tile__hint") { +hint }
    }
}

/**
 * One identity provider's own page — the configuration surface the index links to.
 *
 * Serves three cases from one function: a reserved key with a compiled-in OAuth2 adapter, a
 * brokered OIDC provider, and the add form for a new brokered key. They differ in which fields
 * they carry, not in how they are laid out, and splitting them produced two forms that drifted.
 *
 * [existing] is null for a provider that has not been configured yet, including a reserved one.
 */
internal fun identityProviderDetailPageImpl(
    workspace: Tenant,
    provider: ProviderKey?,
    existing: IdentityProvider?,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    error: String? = null,
    saved: Boolean = false,
    failures: List<SignInFailureRow> = emptyList(),
    baseUrl: String = "",
    probe: AdminResult<DiscoveryProbe>? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug
        val indexUrl = "/admin/workspaces/$slug/settings/identity-providers"
        val isReserved = provider != null && provider in ProviderKey.RESERVED
        val isNew = provider == null
        val heading =
            when {
                isNew -> EnglishStrings.IDP_ADD_TITLE
                else -> existing?.displayName?.takeIf { it.isNotBlank() }
                    ?: EnglishStrings.providerDisplayName(provider!!)
            }
        // A new provider has no key yet, so its callback cannot be resolved server-side. The
        // template carries the placeholder and the key field rewrites it as the operator types —
        // without it they must register a throwaway redirect URI, save here, then go back and
        // correct it at the issuer.
        val callbackUrl = provider?.let { socialCallbackUrl(baseUrl, slug, it) }
        val callbackTemplate = socialCallbackUrlTemplate(baseUrl, slug)
        val formId = "idp-form"

        adminShell(
            pageTitle = "$heading · ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            loggedInAs = loggedInAs,
            activeAppSection = "identity-providers",
            toastMessage = if (saved) EnglishStrings.TOAST_IDP_SAVED else null,
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    slug to "/admin/workspaces/$slug",
                    "Settings" to "/admin/workspaces/$slug/settings",
                    EnglishStrings.IDP_PAGE_TITLE to indexUrl,
                    heading to null,
                )

                div("page-header") {
                    div("page-header__left") {
                        div("page-header__identity") {
                            div("page-header__title-row") {
                                h1("page-header__title") { +heading }
                                providerStatusBadge(existing)
                            }
                        }
                    }
                    // Its own one-field POST, so the switch means what it looks like it means.
                    // As a field of the edit form it did nothing until a Save far below.
                    if (existing != null) {
                        div("page-header__actions") {
                            form(
                                action = "$indexUrl/${provider!!.value}/enabled",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                            ) {
                                hiddenInput(name = "enabled") { value = (!existing.enabled).toString() }
                                button(type = ButtonType.submit) {
                                    classes = setOf("btn", "btn--ghost", "btn--sm")
                                    +(
                                        if (existing.enabled) {
                                            EnglishStrings.IDP_DISABLE_ACTION
                                        } else {
                                            EnglishStrings.IDP_ENABLE_ACTION
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (error != null) errorNotice(error)

                form(
                    action = if (isNew) indexUrl else "$indexUrl/${provider!!.value}",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    id = formId
                    // An existing provider's enabled state is owned by the toggle above; without
                    // this the edit form would post an unchecked box and switch it off on save.
                    hiddenInput(name = "enabled") { value = (existing?.enabled ?: true).toString() }

                    div("ov-card") {
                        div("ov-card__section-label") { +EnglishStrings.IDP_CONNECTION_HEADING }

                        div("setup-row") {
                            div("setup-row__text") { providerSetupInstructions(provider, heading) }
                            div("copy-field") {
                                span("copy-field__value") {
                                    attributes["data-callback-template"] = callbackTemplate
                                    +(callbackUrl ?: callbackTemplate)
                                }
                                button(type = ButtonType.button) {
                                    classes = setOf("copy-field__btn")
                                    attributes["data-copy"] = callbackUrl ?: callbackTemplate
                                    title = "Copy"
                                    inlineSvgIcon("copy", "Copy")
                                }
                            }
                        }

                        if (isNew) {
                            hiddenInput(name = "kind") { value = ProviderKind.OIDC.value }
                            div("edit-row") {
                                span("edit-row__label") { +EnglishStrings.IDP_KEY_LABEL }
                                div {
                                    input(type = InputType.text, name = "providerKey") {
                                        classes = setOf("edit-row__field")
                                        placeholder = EnglishStrings.IDP_KEY_PLACEHOLDER
                                        required = true
                                        attributes["autocomplete"] = "off"
                                        attributes["data-callback-key-input"] = ""
                                    }
                                    div("edit-row__hint") { +EnglishStrings.IDP_KEY_HINT }
                                }
                            }
                        }

                        if (!isReserved) {
                            idpTextRow(
                                label = EnglishStrings.IDP_DISPLAY_NAME_LABEL,
                                fieldName = "displayName",
                                value = existing?.displayName,
                                hint = EnglishStrings.IDP_DISPLAY_NAME_HINT,
                                placeholder = EnglishStrings.IDP_DISPLAY_NAME_PLACEHOLDER,
                            )
                            idpTextRow(
                                label = EnglishStrings.IDP_ISSUER_LABEL,
                                fieldName = "issuer",
                                value = existing?.issuer,
                                hint = EnglishStrings.IDP_ISSUER_HINT,
                                placeholder = EnglishStrings.IDP_ISSUER_PLACEHOLDER,
                                required = true,
                            )
                        }

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
                                    placeholder = "••••••••••"
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

                        // Discovery fills these in. Collapsing them on redirect is correct for an
                        // advanced section, which is why <details> is right here and not for a
                        // whole provider.
                        if (!isReserved) {
                            details("disclosure") {
                                summary { +EnglishStrings.IDP_ADVANCED_SUMMARY }
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
                            }
                        }

                        div("ov-card__actions") {
                            button(type = ButtonType.submit) {
                                classes = setOf("btn", "btn--primary", "btn--sm")
                                +(if (isNew) EnglishStrings.IDP_ADD_BUTTON else EnglishStrings.IDP_SAVE_BUTTON)
                            }
                            // Sits in the same bar but posts elsewhere: a discovery test writes
                            // nothing and must not carry a secret typed but not yet saved.
                            if (existing?.issuer != null) {
                                button(type = ButtonType.submit) {
                                    classes = setOf("btn", "btn--ghost", "btn--sm")
                                    attributes["form"] = "idp-discovery-form"
                                    +EnglishStrings.IDP_DISCOVERY_BUTTON
                                }
                            }
                        }
                    }

                    div("ov-card") {
                        div("ov-card__section-label") { +EnglishStrings.IDP_JIT_TITLE }
                        jitControls(existing)
                    }
                }

                if (existing?.issuer != null) {
                    form(
                        action = "$indexUrl/${provider!!.value}/test-discovery",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) { id = "idp-discovery-form" }
                }

                if (probe != null && callbackUrl != null) {
                    discoveryProbePanel(probe, callbackUrl)
                }

                if (existing != null) {
                    div("ov-card") {
                        div("ov-card__section-label") { +EnglishStrings.IDP_FAILURES_HEADING }
                        identityProviderFailuresPanel(failures)
                    }

                    div("ov-card") {
                        div("ov-card__section-label ov-card__section-label--danger") {
                            +"Danger zone"
                        }
                        div("danger-zone") {
                            dangerZoneCard(
                                title = EnglishStrings.IDP_DELETE_BUTTON,
                                description = EnglishStrings.IDP_DELETE_DESCRIPTION,
                            ) {
                                postButton(
                                    action = "$indexUrl/${provider!!.value}/delete",
                                    label = EnglishStrings.IDP_DELETE_BUTTON,
                                    btnClass = "btn btn--danger btn--sm",
                                    confirmMessage = EnglishStrings.IDP_DELETE_CONFIRM,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

/** Where to register the callback, in the issuer's own words where we know them. */
private fun FlowContent.providerSetupInstructions(
    provider: ProviderKey?,
    providerName: String,
) {
    when (provider) {
        ProviderKey.GOOGLE -> {
            +"Create credentials in "
            a(href = "https://console.cloud.google.com/apis/credentials", target = "_blank") {
                +"Google Cloud Console"
            }
            +". Set the authorized redirect URI to:"
        }
        ProviderKey.GITHUB -> {
            +"Register an OAuth App in "
            a(href = "https://github.com/settings/developers", target = "_blank") {
                +"GitHub Developer Settings"
            }
            +". Set the callback URL to:"
        }
        null -> +EnglishStrings.IDP_CALLBACK_HINT_NEW
        else -> +"Register this workspace with $providerName. Set the callback URL to:"
    }
}

/**
 * The two just-in-time columns: whether a brokered sign-in may create an account here, and which
 * email domains it may create one for.
 *
 * The domains are the same chip grid the API-key scopes use — one chip per domain, ticked. An
 * unticked chip is a removal, which is what lets the control express the empty list at all. The
 * service normalises what is posted (trimmed, lower-cased, de-duplicated), so the chip an operator
 * sees afterwards is the string that was stored, not the string they typed.
 */
private fun FlowContent.jitControls(existing: IdentityProvider?) {
    val domains = existing?.jitAllowedDomains.orEmpty()

    div("edit-row") {
        span("edit-row__label") { +EnglishStrings.IDP_JIT_TITLE }
        div {
            label("toggle") {
                input(type = InputType.checkBox, name = "jitEnabled") {
                    attributes["value"] = "true"
                    if (existing?.jitEnabled == true) checked = true
                }
                span("toggle__track") { span("toggle__thumb") {} }
                span("toggle__label toggle__label--muted") { +EnglishStrings.IDP_JIT_ENABLE_LABEL }
            }
            div("edit-row__hint") { +EnglishStrings.IDP_JIT_HINT }
        }
    }

    div("edit-row") {
        span("edit-row__label") { +EnglishStrings.IDP_TRUST_EMAIL_LABEL }
        div {
            label("toggle") {
                input(type = InputType.checkBox, name = "trustEmailClaim") {
                    attributes["value"] = "true"
                    if (existing?.trustEmailClaim == true) checked = true
                }
                span("toggle__track") { span("toggle__thumb") {} }
                span("toggle__label toggle__label--muted") { +EnglishStrings.IDP_TRUST_EMAIL_TOGGLE }
            }
            div("edit-row__hint") { +EnglishStrings.IDP_TRUST_EMAIL_HINT }
        }
    }

    div("edit-row") {
        span("edit-row__label") { +EnglishStrings.IDP_JIT_DOMAINS_LABEL }
        div {
            if (domains.isEmpty()) {
                // Only for a row that exists. Empty on a saved provider means auto-creation is off;
                // empty on the add form means not configured yet, and those are opposite meanings.
                // Saying "off" about a provider nobody has saved would state the wrong one.
                if (existing != null) {
                    notice {
                        div("notice__desc") { +EnglishStrings.IDP_JIT_DOMAINS_EMPTY }
                    }
                }
            } else {
                div("chip-grid") {
                    domains.forEach { domain ->
                        label("scope-chip") {
                            input(type = InputType.checkBox, name = "jitAllowedDomains") {
                                value = domain
                                checked = true
                            }
                            span("scope-chip__label") { +domain }
                        }
                    }
                }
                div("edit-row__hint") { +EnglishStrings.IDP_JIT_DOMAINS_HINT }
            }
        }
    }

    div("edit-row") {
        span("edit-row__label") { +EnglishStrings.IDP_JIT_DOMAIN_ADD_LABEL }
        div {
            input(type = InputType.text, name = "jitAllowedDomainToAdd") {
                classes = setOf("edit-row__field")
                placeholder = EnglishStrings.IDP_JIT_DOMAIN_ADD_PLACEHOLDER
                attributes["autocomplete"] = "off"
            }
            div("edit-row__hint") { +EnglishStrings.IDP_JIT_DOMAIN_ADD_HINT }
        }
    }
}

/**
 * The result of a discovery test, stated as the half of setup it is.
 *
 * The "did not verify" half is not a caveat appended to a success — it is the part an operator has
 * to act on, because the failure it names (a redirect URI the provider does not recognise) is
 * invisible from this side and surfaces only when a real person is turned away at the provider.
 * A tick that quietly covered both halves would convert an operator's uncertainty into false
 * confidence, which is worse than no tick at all.
 */
private fun FlowContent.discoveryProbePanel(
    probe: AdminResult<DiscoveryProbe>,
    callbackUrl: String,
) {
    div("edit-row") {
        span("edit-row__label") { +EnglishStrings.IDP_DISCOVERY_TITLE }
        div {
            when (probe) {
                is AdminResult.Failure -> {
                    notice(modifier = "notice--error") {
                        div("notice__title") { +EnglishStrings.IDP_DISCOVERY_FAILED_TITLE }
                        div("notice__desc") { +probe.error.message }
                    }
                }
                is AdminResult.Success -> {
                    val report = probe.value
                    div("edit-row__hint") { +EnglishStrings.IDP_DISCOVERY_VERIFIED_TITLE }
                    table("data-table") {
                        tbody {
                            probeRow(EnglishStrings.IDP_ISSUER_LABEL, report.issuer)
                            probeRow(EnglishStrings.IDP_AUTHORIZATION_ENDPOINT_LABEL, report.authorizationEndpoint)
                            probeRow(EnglishStrings.IDP_TOKEN_ENDPOINT_LABEL, report.tokenEndpoint)
                            probeRow(EnglishStrings.IDP_JWKS_URI_LABEL, report.jwksUri)
                            probeRow(
                                EnglishStrings.IDP_DISCOVERY_KEYS_LABEL,
                                report.verificationKeyCount?.toString()
                                    ?: (report.keySetProblem ?: EnglishStrings.IDP_DISCOVERY_KEYS_UNREAD),
                            )
                        }
                    }
                }
            }

            // Rendered on both outcomes: a resolved document is exactly when an operator is most
            // likely to believe setup is finished, and a failed one still leaves this to register.
            notice {
                div("notice__title") { +EnglishStrings.IDP_DISCOVERY_NOT_VERIFIED_TITLE }
                div("notice__desc") { +EnglishStrings.IDP_DISCOVERY_NOT_VERIFIED_REDIRECT }
                div("notice__desc") { +EnglishStrings.IDP_DISCOVERY_NOT_VERIFIED_CREDENTIALS }
            }
            div("edit-row__hint") { +EnglishStrings.IDP_DISCOVERY_CALLBACK_LABEL }
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
}

private fun TBODY.probeRow(
    label: String,
    value: String,
) {
    tr {
        td { +label }
        td { +value }
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
        pageTitle = "API Keys · ${workspace.displayName}",
        activeRail = "settings",
        allWorkspaces = allWorkspaces,
        workspaceName = workspace.displayName,
        workspaceSlug = slug,
        workspaceLogoUrl = workspace.theme.logoUrl,
        loggedInAs = loggedInAs,
        activeAppSection = "api-keys",
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
                        +"Machine-to-machine authentication. "
                        +EnglishStrings.API_KEY_SHOWN_ONCE
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
            notice(modifier = "notice--success", iconName = "check-circle") {
                p {
                    +"API key created. "
                    +EnglishStrings.SECRET_SHOWN_ONCE
                }
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
            errorNotice(error)
        }

        // ── Keys table / empty state ─────────────────────────────
        if (apiKeys.isEmpty() && newKeyRaw == null) {
            emptyState(
                iconName = "key",
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
            table("data-table") {
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
                                span("data-table__name") { +key.name }
                                if (key.bootstrapName != null) {
                                    span("badge badge--muted badge--inline") { +"Bootstrapped" }
                                }
                            }
                            td { span("data-table__meta") { +"${key.keyPrefix}\u2026" } }
                            td {
                                span("data-table__meta") { +key.scopes.joinToString(", ") }
                            }
                            td {
                                span("data-table__meta") {
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
                                span("data-table__meta") {
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
                                        span("data-table__meta data-table__meta--with-icon") {
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
        pageTitle = "New API Key · ${workspace.displayName}",
        activeRail = "settings",
        allWorkspaces = allWorkspaces,
        workspaceName = workspace.displayName,
        workspaceSlug = slug,
        workspaceLogoUrl = workspace.theme.logoUrl,
        loggedInAs = loggedInAs,
        activeAppSection = "api-keys",
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
                        +EnglishStrings.API_KEY_SHOWN_ONCE
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
            errorNotice(error)
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
