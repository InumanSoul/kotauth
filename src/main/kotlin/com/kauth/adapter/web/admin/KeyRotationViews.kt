package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantKey
import kotlinx.html.*

internal fun keyManagementPageImpl(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    keys: List<TenantKey>,
    error: String? = null,
    toastMessage: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "Signing Keys — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "signing-keys",
            loggedInAs = loggedInAs,
            contentClass = "content-outer",
            toastMessage = toastMessage,
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    slug to "/admin/workspaces/$slug",
                    "Settings" to "/admin/workspaces/$slug/settings",
                    "Signing Keys" to null,
                )

                pageHeader(
                    title = "Signing Keys",
                    subtitle = "RS256 key pairs used to sign JWTs for this workspace.",
                    actions = {
                        postButton(
                            action = "/admin/workspaces/$slug/settings/signing-keys/rotate",
                            label = "Rotate Signing Key",
                            btnClass = "btn btn--primary",
                            confirmMessage = "Generate a new signing key? The current key will remain " +
                                "active for verification until manually retired.",
                        )
                    },
                )

                if (error != null) {
                    div("notice notice--error") { +error }
                }

                div("ov-card") {
                    div("ov-card__section-label") { +"Signing Key History" }
                    if (keys.isEmpty()) {
                        emptyState(
                            iconName = "security",
                            title = "No keys",
                            description = "No signing keys have been provisioned for this workspace.",
                        )
                    } else {
                        table("data-table") {
                            thead {
                                tr {
                                    th { +"Key ID" }
                                    th { +"Created" }
                                    th { style = "width:140px;"; +"Status" }
                                    th { style = "width:80px;" }
                                }
                            }
                            tbody {
                                keys.forEach { key ->
                                    tr {
                                        td {
                                            span("data-table__id") { +key.keyId }
                                        }
                                        td {
                                            +(key.createdAt?.toDisplayString() ?: "—")
                                        }
                                        td {
                                            if (key.active) {
                                                span("badge badge--active") {
                                                    span("badge__dot") {}
                                                    +"Active"
                                                }
                                            } else if (key.enabled) {
                                                span("badge badge--warn") {
                                                    span("badge__dot") {}
                                                    +"Verification only"
                                                }
                                            } else {
                                                span("badge badge--inactive") { +"Retired" }
                                            }
                                        }
                                        td {
                                            if (!key.active && key.enabled) {
                                                div {
                                                    postButton(
                                                        action =
                                                            "/admin/workspaces/$slug/settings/signing-keys/${key.keyId}/retire",
                                                        label = "Retire",
                                                        btnClass = "btn btn--danger btn--sm",
                                                        confirmMessage =
                                                            "Retire this key? Active sessions and tokens signed by it " +
                                                            "will immediately stop working. This cannot be undone.",
                                                    )
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
