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

            // ── Overview section ─────────────────────────────────────
            section(title = "Overview") {
                ovCard {
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
            }

            // ── Redirect URIs section ────────────────────────────────
            section(
                title = "Redirect URIs",
                action = {
                    a(
                        href = "/admin/workspaces/${workspace.slug}/applications/${application.clientId}/edit",
                        classes = "section__action",
                    ) { +"+ Add URI" }
                },
            ) {
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
                    ovCard {
                        application.redirectUris.forEach { uri ->
                            div("ov-card__row") {
                                span("ov-card__value ov-card__value--mono") { +uri }
                            }
                        }
                    }
                }
            }

            divider()

            // ── Danger zone ──────────────────────────────────────────
            section(title = "Danger zone", danger = true) {
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
                        +"Register a new OAuth2 / OIDC application in the "
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
                    div("field") {
                        label {
                            htmlFor = "clientId"
                            +"Client ID"
                        }
                        input(type = InputType.text, name = "clientId") {
                            id = "clientId"
                            placeholder = "my-frontend"
                            required = true
                            value = prefill.clientId
                            attributes["pattern"] = "[a-zA-Z0-9._-]+"
                        }
                        p("field-hint") { +"Unique identifier, e.g. my-frontend. Immutable after creation." }
                    }
                    div("field") {
                        label {
                            htmlFor = "name"
                            +"Name"
                        }
                        input(type = InputType.text, name = "name") {
                            id = "name"
                            placeholder = "My Frontend App"
                            required = true
                            value = prefill.name
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
                        p("field-hint") { +"One URI per line. These are allowed OAuth2 callback URLs." }
                    }
                    div("form-actions") {
                        button(type = ButtonType.submit, classes = "btn") { +"Create Application" }
                        a("/admin/workspaces/${workspace.slug}", classes = "btn btn-ghost") { +"Cancel" }
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
