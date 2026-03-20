package com.kauth.adapter.web.admin

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
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
    allWorkspaces: List<Pair<String, String>>,
    allApps: List<Application>,
    loggedInAs: String,
    newSecret: String? = null,
): HTML.() -> Unit =
    {
        val appPairs = allApps.map { it.clientId to it.name }
        adminShell(
            pageTitle = "${application.name} — ${workspace.displayName}",
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            apps = appPairs,
            activeAppSlug = application.clientId,
            activeAppSection = "overview",
            loggedInAs = loggedInAs,
        ) {
            breadcrumb(
                "Workspaces" to "/admin",
                workspace.slug to "/admin/workspaces/${workspace.slug}",
                application.clientId to null,
            )

            // ── Page header ──────────────────────────────────────────
            div("page-header") {
                div("page-header__left") {
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
                div("page-header__actions") {
                    ghostLinkExternal(
                        "/t/${workspace.slug}/login?client_id=${application.clientId}",
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
                notice(
                    title = "New Client Secret (copy now — shown once)",
                    description = newSecret,
                )
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

// Create application form.
internal fun createApplicationPageImpl(
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
            breadcrumb(
                "Workspaces" to "/admin",
                workspace.slug to "/admin/workspaces/${workspace.slug}",
                "New Application" to null,
            )

            // ── Page header with external submit ───────────────────
            div("page-header") {
                div("page-header__left") {
                    h1("page-header__title") { +"Create Application" }
                    p("page-header__sub") {
                        +"Register a new OAuth2 / OIDC application in the "
                        strong { +workspace.displayName }
                        +" workspace."
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
                        div("edit-row__hint") { +"One URI per line. These are allowed OAuth2 callback URLs." }
                    }
                }
            }
        }
    }

// Edit application form.
internal fun editApplicationPageImpl(
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
                    h1("page-header__title") { +"Edit Application" }
                    p("page-header__sub") { +"Update settings for ${application.name}." }
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
                        div("edit-row__hint") { +"One URI per line." }
                    }
                }
            }
        }
    }
