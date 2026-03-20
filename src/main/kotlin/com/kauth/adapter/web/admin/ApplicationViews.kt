package com.kauth.adapter.web.admin

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.Tenant
import kotlinx.html.*

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
