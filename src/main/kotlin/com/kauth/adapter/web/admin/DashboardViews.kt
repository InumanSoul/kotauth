package com.kauth.adapter.web.admin

import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.Tenant
import kotlinx.html.*

/**
 * Workspace management list — accessible via "Manage workspaces" in the
 * topbar dropdown. Shows all workspaces with a CTA to create new ones.
 */
internal fun workspaceListPageImpl(
    workspaces: List<Tenant>,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Workspaces",
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            workspaceName = "KotAuth",
            workspaceSlug = null,
            loggedInAs = loggedInAs,
                  contentClass = "content-outer",
) {
            div("content-inner content-inner--wide") {
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Workspaces" }
                        p("page-header__sub") { +"All authorization boundary workspaces" }
                    }
                }
                div("page-header__actions") {
                    primaryLink("/admin/workspaces/new", "New Workspace")
                }
            }

            if (workspaces.isEmpty()) {
                emptyState(
                    iconName = "admin",
                    title = "No workspaces yet",
                    description = "Create your first workspace to get started.",
                ) {
                    a(
                        href = "/admin/workspaces/new",
                        classes = "empty-state__cta",
                    ) { +"+ New Workspace" }
                }
            } else {
                div("ov-card") {
                    table("data-table") {
                        thead {
                            tr {
                                th { style = "width:200px;"; +"Slug" }
                                th { +"Name" }
                                th { style = "width:130px;"; +"Registration" }
                                th { style = "width:70px;" }
                            }
                        }
                        tbody {
                            workspaces.forEach { ws ->
                                tr {
                                    td {
                                        a(
                                            href = "/admin/workspaces/${ws.slug}",
                                            classes = "data-table__id",
                                        ) { +ws.slug }
                                    }
                                    td { span("data-table__name") { +ws.displayName } }
                                    td {
                                        if (ws.registrationEnabled) {
                                            span("badge badge--active") {
                                                span("badge__dot") {}
                                                +"Enabled"
                                            }
                                        } else {
                                            span("badge badge--inactive") {
                                                span("badge__dot") {}
                                                +"Disabled"
                                            }
                                        }
                                    }
                                    td {
                                        div("data-table__actions") {
                                            a(
                                                href = "/admin/workspaces/${ws.slug}",
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
}
    }
