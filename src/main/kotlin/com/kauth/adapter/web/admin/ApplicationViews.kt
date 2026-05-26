package com.kauth.adapter.web.admin

import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.Role
import com.kauth.domain.model.Tenant
import kotlinx.html.*

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

internal fun applicationDetailPageImpl(
    workspace: Tenant,
    application: Application,
    allWorkspaces: List<WorkspaceStub>,
    allApps: List<Application>,
    loggedInAs: String,
    newSecret: String? = null,
    defaultRoles: List<Role> = emptyList(),
    // Tenant-scoped + this app's client-scoped roles, minus those already set.
    availableDefaultRoles: List<Role> = emptyList(),
): HTML.() -> Unit =
    {
        val appPairs = allApps.map { it.clientId to it.name }
        adminShell(
            pageTitle = "${application.name} — ${workspace.displayName}",
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            apps = appPairs,
            activeAppSlug = application.clientId,
            activeAppSection = "overview",
            loggedInAs = loggedInAs,
                    contentClass = "content-outer",
) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                workspace.slug to "/admin/workspaces/${workspace.slug}",
                application.clientId to null,
            )

            // ── Page header ──────────────────────────────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        div("page-header__title-row") {
                            h1("page-header__title") { +application.name }
                            if (application.enabled) {
                                span("badge badge--active") {
                                    span("badge__dot") {}
                                    +"Active"
                                }
                            } else {
                                span("badge badge--inactive") {
                                    span("badge__dot") {}
                                    +"Disabled"
                                }
                            }
                        }
                        div("page-header__meta") {
                            span("badge badge--id") { +application.clientId }
                            when (application.accessType) {
                                AccessType.PUBLIC -> span("badge badge--public") { +"Public" }
                                AccessType.CONFIDENTIAL -> span("badge badge--confidential") { +"Confidential" }
                                AccessType.BEARER_ONLY -> span("badge badge--public") { +"Bearer Only" }
                            }
                        }
                    }
                }
                div("page-header__actions") {
                    ghostLinkExternal(
                        "/t/${workspace.slug}/authorize?client_id=${application.clientId}",
                        "Open Login",
                    )
                    primaryLink(
                        "/admin/workspaces/${workspace.slug}/applications/${application.clientId}/edit",
                        "Edit Application",
                    )
                }
            }

            // ── New secret banner (shown once after regeneration) ────
            if (newSecret != null) {
                div("notice notice--success") {
                    p { +"New Client Secret — copy it now. You will not see it again." }
                    div("copy-field") {
                        span("copy-field__value") { +newSecret }
                        button(type = ButtonType.button) {
                            classes = setOf("copy-field__btn")
                            attributes["data-copy"] = newSecret
                            title = "Copy"
                            inlineSvgIcon("copy", "Copy")
                        }
                    }
                }
            }

            // ── Overview ────────────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Overview" }
                ovRowMono("Client ID", application.clientId, copyable = true)
                ovRowText("Name", application.name)
                if (application.description.isNullOrBlank()) {
                    ovRowMuted("Description", "No description")
                } else {
                    ovRowMuted("Description", application.description)
                }
                ovRow("Access Type") {
                    when (application.accessType) {
                        AccessType.PUBLIC -> span("badge badge--public") { +"Public" }
                        AccessType.CONFIDENTIAL -> span("badge badge--confidential") { +"Confidential" }
                        AccessType.BEARER_ONLY -> span("badge badge--public") { +"Bearer Only" }
                    }
                }
                ovRow("Workspace") {
                    a(
                        href = "/admin/workspaces/${workspace.slug}",
                        classes = "badge badge--id",
                    ) { +workspace.slug }
                }
                ovRowInherited(
                    "Token TTL, security and branding",
                    "/admin/workspaces/${workspace.slug}/settings",
                )
            }

            // ── Redirect URIs ──────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") {
                    +"Redirect URIs"
                    a(
                        href = "/admin/workspaces/${workspace.slug}/applications/${application.clientId}/edit",
                        classes = "btn btn--ghost btn--sm",
                    ) { +"+ Add URI" }
                }
                if (application.redirectUris.isEmpty()) {
                    emptyState(
                        iconName = "redirect",
                        title = "No redirect URIs configured",
                        description = "Login callbacks will be blocked until at least one allowed URI is registered.",
                    ) {
                        a(
                            href = "/admin/workspaces/${workspace.slug}/applications/${application.clientId}/edit",
                            classes = "empty-state__cta",
                        ) { +"+ Add Redirect URI" }
                    }
                } else {
                    application.redirectUris.forEach { uri ->
                        div("ov-card__row") {
                            span("ov-card__value ov-card__value--mono") { +uri }
                        }
                    }
                }
            }

            // ── Launcher ───────────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") {
                    +"Launcher"
                    a(
                        href = "/admin/workspaces/${workspace.slug}/applications/${application.clientId}/edit",
                        classes = "btn btn--ghost btn--sm",
                    ) { +"Configure" }
                }
                if (application.launcherUrl.isNullOrBlank()) {
                    emptyState(
                        iconName = "redirect",
                        title = "Not in the launcher",
                        description = "Set a launcher URL to surface this app in the workspace launcher.",
                    ) {
                        a(
                            href = "/admin/workspaces/${workspace.slug}/applications/${application.clientId}/edit",
                            classes = "empty-state__cta",
                        ) { +"+ Set launcher URL" }
                    }
                } else {
                    ovRowMono("Launcher URL", application.launcherUrl, copyable = true)
                    if (!application.iconUrl.isNullOrBlank()) {
                        ovRowMono("Icon URL", application.iconUrl, copyable = true)
                    } else {
                        ovRowMuted("Icon URL", "Using letter fallback")
                    }
                    ovRow("Visibility") {
                        if (application.launcherVisible) {
                            span("badge badge--active") {
                                span("badge__dot") {}
                                +"Visible"
                            }
                        } else {
                            span("badge badge--inactive") {
                                span("badge__dot") {}
                                +"Hidden"
                            }
                        }
                    }
                    ovRowText("Display order", application.launcherDisplayOrder.toString())
                }
            }

            // ── Registration Defaults ──────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Registration Defaults" }
                if (defaultRoles.isEmpty()) {
                    p("edit-row__hint") {
                        style = "padding:8px 16px 12px;"
                        +"No default roles configured — roles added here are granted "
                        +"automatically to users who self-register through this app."
                    }
                } else {
                    p("edit-row__hint") {
                        style = "padding:0 16px 4px;"
                        +"Roles granted automatically to users who self-register through this "
                        +"application. Does not affect users created by an admin."
                    }
                    table("data-table") {
                        thead {
                            tr {
                                th { +"Role" }
                                th { style = "width:80px;" }
                            }
                        }
                        tbody {
                            defaultRoles.forEach { role ->
                                tr {
                                    td { span("data-table__name") { +role.name } }
                                    td {
                                        form(
                                            action =
                                                "/admin/workspaces/${workspace.slug}/applications/" +
                                                    "${application.clientId}/default-roles/remove",
                                            method = FormMethod.post,
                                        ) {
                                            input(type = InputType.hidden, name = "roleId") {
                                                value = role.id?.value.toString()
                                            }
                                            button(type = ButtonType.submit) {
                                                classes = setOf("btn", "btn--ghost", "btn--sm")
                                                +"Remove"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (availableDefaultRoles.isNotEmpty()) {
                    div("edit-actions") {
                        form(
                            action =
                                "/admin/workspaces/${workspace.slug}/applications/" +
                                    "${application.clientId}/default-roles",
                            method = FormMethod.post,
                        ) {
                            style = "display:flex; align-items:center; gap:8px;"
                            select {
                                classes = setOf("edit-row__field", "edit-row__field--select")
                                name = "roleId"
                                availableDefaultRoles.forEach { role ->
                                    option {
                                        value = role.id?.value.toString()
                                        +role.name
                                    }
                                }
                            }
                            button(type = ButtonType.submit) {
                                classes = setOf("btn", "btn--primary")
                                +"Add Role"
                            }
                        }
                    }
                }
            }

            // ── Danger zone ──────────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label ov-card__section-label--danger") { +"Danger zone" }
                div("danger-zone") {
                    dangerZoneCard(
                        title = "Disable this application",
                        description = "All login attempts will be rejected. This can be reversed at any time.",
                    ) {
                        postButton(
                            action = "/admin/workspaces/${workspace.slug}/applications/${application.clientId}/toggle",
                            label = if (application.enabled) "Disable" else "Enable",
                            btnClass = "btn btn--danger btn--sm",
                        )
                    }
                    if (application.accessType == AccessType.CONFIDENTIAL) {
                        dangerZoneCard(
                            title = "Regenerate client secret",
                            description =
                                "The current secret will be invalidated immediately. All " +
                                    "integrations using it will break.",
                            warning = true,
                        ) {
                            postButton(
                                action =
                                    "/admin/workspaces/${workspace.slug}/applications/" +
                                        "${application.clientId}/regenerate-secret",
                                label = "Regenerate",
                                btnClass = "btn btn--warning btn--sm",
                            )
                        }
                    }
                }
            }
                    }
}
    }

// Create application form.
internal fun createApplicationPageImpl(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    error: String? = null,
    prefill: ApplicationPrefill = ApplicationPrefill(),
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "New Application — ${workspace.displayName}",
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            loggedInAs = loggedInAs,
            showSidebar = false,
            contentClass = "content-outer",
        ) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                workspace.slug to "/admin/workspaces/${workspace.slug}",
                "New Application" to null,
            )

            // ── Page header with external submit ───────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Create Application" }
                        p("page-header__sub") {
                            +"Register a new OAuth2 / OIDC application in the "
                            strong { +workspace.displayName }
                            +" workspace."
                        }
                    }
                }
                div("page-header__actions") {
                    a("/admin/workspaces/${workspace.slug}", classes = "btn btn--ghost") { +"Cancel" }
                    button(type = ButtonType.submit, classes = "btn btn--primary") {
                        attributes["form"] = "create-app-form"
                        +"Create Application"
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
                    action = "/admin/workspaces/${workspace.slug}/applications",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    id = "create-app-form"

                    div("edit-row") {
                        span("edit-row__label") { +"Client ID" }
                        div {
                            input(type = InputType.text, name = "clientId") {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                this.id = "clientId"
                                placeholder = "my-frontend"
                                required = true
                                value = prefill.clientId
                                attributes["pattern"] = "[a-zA-Z0-9._-]+"
                            }
                            div("edit-row__hint") {
                                +"Unique identifier, e.g. my-frontend. Immutable after creation."
                            }
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Name" }
                        input(type = InputType.text, name = "name") {
                            classes = setOf("edit-row__field")
                            this.id = "name"
                            placeholder = "My Frontend App"
                            required = true
                            value = prefill.name
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Description" }
                        div {
                            input(type = InputType.text, name = "description") {
                                classes = setOf("edit-row__field")
                                this.id = "description"
                                placeholder = "Short description of this application"
                                value = prefill.description
                            }
                            div("edit-row__hint") { +"Optional." }
                        }
                    }
                }
            }

            // ── Access card ────────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Access" }
                div("edit-row") {
                    span("edit-row__label") { +"Access Type" }
                    select {
                        attributes["form"] = "create-app-form"
                        name = "accessType"
                        id = "accessType"
                        classes = setOf("edit-row__field")
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
                div("edit-row") {
                    span("edit-row__label") { +"Redirect URIs" }
                    div {
                        textArea {
                            attributes["form"] = "create-app-form"
                            name = "redirectUris"
                            id = "redirectUris"
                            rows = "4"
                            classes = setOf("edit-row__field")
                            attributes["placeholder"] =
                                "https://app.example.com/callback\nhttps://localhost:3000/callback"
                            +prefill.redirectUris
                        }
                        div("edit-row__hint") {
                            +"One URI per line. Their origins are automatically CORS-allowed "
                            +"for SPAs in this workspace."
                        }
                    }
                }
            }
                    }
}
    }

// Edit application form.
internal fun editApplicationPageImpl(
    workspace: Tenant,
    application: Application,
    allWorkspaces: List<WorkspaceStub>,
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
            workspaceLogoUrl = workspace.theme.logoUrl,
            apps = appPairs,
            activeAppSlug = application.clientId,
            activeAppSection = "overview",
            loggedInAs = loggedInAs,
                    contentClass = "content-outer",
) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                workspace.slug to "/admin/workspaces/${workspace.slug}",
                application.clientId to
                    "/admin/workspaces/${workspace.slug}/applications/${application.clientId}",
                "Edit" to null,
            )

            // ── Page header with external submit ───────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Edit Application" }
                        p("page-header__sub") { +"Update settings for ${application.name}." }
                    }
                }
                div("page-header__actions") {
                    a(
                        "/admin/workspaces/${workspace.slug}/applications/${application.clientId}",
                        classes = "btn btn--ghost",
                    ) { +"Cancel" }
                    button(type = ButtonType.submit, classes = "btn btn--primary") {
                        attributes["form"] = "edit-app-form"
                        +"Save Changes"
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
                    action = "/admin/workspaces/${workspace.slug}/applications/${application.clientId}/edit",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    id = "edit-app-form"

                    div("edit-row") {
                        span("edit-row__label") { +"Client ID" }
                        div {
                            input(type = InputType.text) {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                disabled = true
                                value = application.clientId
                            }
                            div("edit-row__hint") {
                                +"Client ID is immutable — it may appear in issued tokens."
                            }
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Name" }
                        input(type = InputType.text, name = "name") {
                            classes = setOf("edit-row__field")
                            this.id = "name"
                            required = true
                            value = application.name
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Description" }
                        div {
                            input(type = InputType.text, name = "description") {
                                classes = setOf("edit-row__field")
                                this.id = "description"
                                placeholder = "Short description of this application"
                                value = application.description ?: ""
                            }
                            div("edit-row__hint") { +"Optional." }
                        }
                    }
                }
            }

            // ── Access card ────────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Access" }
                div("edit-row") {
                    span("edit-row__label") { +"Access Type" }
                    select {
                        attributes["form"] = "edit-app-form"
                        name = "accessType"
                        id = "accessType"
                        classes = setOf("edit-row__field")
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
                div("edit-row") {
                    span("edit-row__label") { +"Redirect URIs" }
                    div {
                        textArea {
                            attributes["form"] = "edit-app-form"
                            name = "redirectUris"
                            id = "redirectUris"
                            rows = "4"
                            classes = setOf("edit-row__field")
                            attributes["placeholder"] =
                                "https://app.example.com/callback\nhttps://localhost:3000/callback"
                            +application.redirectUris.joinToString("\n")
                        }
                        div("edit-row__hint") {
                            +"One URI per line. Their origins are automatically CORS-allowed "
                            +"for SPAs in this workspace."
                        }
                    }
                }
                div("edit-row") {
                    span("edit-row__label") { +"Token Audience" }
                    div {
                        input(type = InputType.text, name = "audience") {
                            attributes["form"] = "edit-app-form"
                            classes = setOf("edit-row__field", "edit-row__field--mono")
                            this.id = "audience"
                            placeholder = "https://api.example.com"
                            value = application.audience ?: ""
                        }
                        div("edit-row__hint") {
                            +"Sets the "
                            code { +"aud" }
                            +" claim in issued JWTs. Leave blank to use the client ID as the audience."
                        }
                    }
                }
            }

            // ── Launcher card ──────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Launcher" }
                div("edit-row") {
                    span("edit-row__label") { +"Launcher URL" }
                    div {
                        input(type = InputType.url, name = "launcherUrl") {
                            attributes["form"] = "edit-app-form"
                            classes = setOf("edit-row__field")
                            this.id = "launcherUrl"
                            placeholder = "https://app.example.com/"
                            value = application.launcherUrl ?: ""
                        }
                        div("edit-row__hint") {
                            +"Public URL the launcher tile navigates to. Leave blank to omit "
                            +"this app from the workspace launcher. Origin must match one of the "
                            +"redirect URIs above."
                        }
                    }
                }
                div("edit-row") {
                    span("edit-row__label") { +"Icon URL" }
                    div {
                        input(type = InputType.url, name = "iconUrl") {
                            attributes["form"] = "edit-app-form"
                            classes = setOf("edit-row__field")
                            this.id = "iconUrl"
                            placeholder = "https://cdn.example.com/icon.svg"
                            value = application.iconUrl ?: ""
                        }
                        div("edit-row__hint") {
                            +"Optional. May be served from a different origin (e.g. CDN). "
                            +"Falls back to the first letter of the app name when blank or unreachable."
                        }
                    }
                }
                label("check-row") {
                    input(type = InputType.checkBox, name = "launcherVisible") {
                        attributes["form"] = "edit-app-form"
                        if (application.launcherVisible) checked = true
                        attributes["value"] = "true"
                    }
                    div("check-row__body") {
                        span("check-row__label") { +"Show in launcher" }
                        span("check-row__desc") {
                            +"Hide this app from the launcher without unsetting the URL above."
                        }
                    }
                }
                div("edit-row") {
                    span("edit-row__label") { +"Display order" }
                    div {
                        input(type = InputType.number, name = "launcherDisplayOrder") {
                            attributes["form"] = "edit-app-form"
                            classes = setOf("edit-row__field")
                            this.id = "launcherDisplayOrder"
                            attributes["min"] = "0"
                            attributes["max"] = "9999"
                            value = application.launcherDisplayOrder.toString()
                        }
                        div("edit-row__hint") {
                            +"Lower numbers appear first. Ties are broken alphabetically by name."
                        }
                    }
                }
            }
                    }
}
    }
