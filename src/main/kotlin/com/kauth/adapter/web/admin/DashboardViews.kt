package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import kotlinx.html.*

// Dashboard — workspace list overview.
internal fun dashboardPageImpl(
    workspaces: List<Tenant>,
    loggedInAs: String,
): HTML.() -> Unit =
    {
        val wsPairs = workspaces.map { it.slug to it.displayName }
        adminShell(
            pageTitle = "Workspaces",
            activeRail = "apps",
            allWorkspaces = wsPairs,
            workspaceName = "KotAuth",
            workspaceSlug = null, // no workspace selected — ctx panel shows empty state
            loggedInAs = loggedInAs,
        ) {
            div("stat-grid") {
                div("stat-card") {
                    div("stat-label") { +"Total Workspaces" }
                    div("stat-value") { +"${workspaces.size}" }
                }
                div("stat-card") {
                    div("stat-label") { +"Registration Enabled" }
                    div("stat-value") { +"${workspaces.count { it.registrationEnabled }}" }
                }
                div("stat-card") {
                    div("stat-label") { +"Master Workspace" }
                    div("stat-value") { +"1" }
                }
            }

            div("page-header") {
                div {
                    p("page-title") { +"Workspaces" }
                    p("page-subtitle") { +"All authorization boundary workspaces" }
                }
            }

            div("card") {
                if (workspaces.isEmpty()) {
                    div("empty-state") {
                        div("empty-state-icon") { +"◫" }
                        p("empty-state-text") { +"No workspaces yet." }
                        p("empty-state-text") {
                            style = "margin-top:0.5rem; font-size:0.8rem;"
                            +"Use the "
                            strong { +"New Workspace" }
                            +" button in the top bar to create your first one."
                        }
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Slug" }
                                th { +"Name" }
                                th { +"Registration" }
                                th { +"Token TTL" }
                                th { +"" }
                            }
                        }
                        tbody {
                            workspaces.forEach { ws ->
                                tr {
                                    td { span("td-code") { +ws.slug } }
                                    td { +ws.displayName }
                                    td {
                                        if (ws.registrationEnabled) {
                                            span("badge badge-green") { +"Enabled" }
                                        } else {
                                            span("badge badge-red") { +"Disabled" }
                                        }
                                    }
                                    td { span("td-muted") { +"${ws.tokenExpirySeconds}s" } }
                                    td {
                                        a("/admin/workspaces/${ws.slug}", classes = "btn btn-ghost btn-sm") {
                                            +"Open →"
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
