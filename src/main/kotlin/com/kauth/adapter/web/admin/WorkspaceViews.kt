package com.kauth.adapter.web.admin

import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.Application
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
        ) {
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
                                inlineSvgIcon("slug", "slug")
                                +workspace.slug
                            }
                        }
                    }
                }
                div("page-header__actions") {
                    ghostLinkExternal("/t/${workspace.slug}/login", "Open Login")
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

            // ── Applications table ───────────────────────────────────
            div("section__header") {
                div {
                    div("section__title") { +"Applications" }
                    div("section__desc") { +"OAuth2 / OIDC clients registered in this workspace" }
                }
            }

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
                                        href = "/admin/workspaces/${workspace.slug}/applications/${app.clientId}",
                                        classes = "data-table__id",
                                    ) { +app.clientId }
                                }
                                td { span("data-table__name") { +app.name } }
                                td {
                                    span("badge badge--public") { +app.accessType.label.uppercase() }
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
                                            href = "/admin/workspaces/${workspace.slug}/applications/${app.clientId}",
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
