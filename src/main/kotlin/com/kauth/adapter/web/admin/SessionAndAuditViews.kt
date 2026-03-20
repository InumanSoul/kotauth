package com.kauth.adapter.web.admin

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import kotlinx.html.*

// Active sessions (workspace-wide).
internal fun activeSessionsPageImpl(
    workspace: Tenant,
    sessions: List<Session>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Sessions — ${workspace.displayName}",
            activeRail = "security",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "sessions",
            loggedInAs = loggedInAs,
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"Sessions" }
            }
            div("page-header") {
                div {
                    p("page-title") { +"Active Sessions" }
                    p(
                        "page-subtitle",
                    ) { +"${sessions.size} active session${if (sessions.size != 1) "s" else ""} in this workspace" }
                }
            }
            div("card") {
                if (sessions.isEmpty()) {
                    div("empty-state") {
                        div("empty-state-icon") { +"⊘" }
                        p("empty-state-text") { +"No active sessions." }
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Session ID" }
                                th { +"User" }
                                th { +"Client" }
                                th { +"IP Address" }
                                th { +"Created" }
                                th { +"Expires" }
                                th { +"" }
                            }
                        }
                        tbody {
                            sessions.forEach { s ->
                                tr {
                                    td { span("td-code") { +"#${s.id}" } }
                                    td { span("td-muted") { +(s.userId?.toString() ?: "M2M") } }
                                    td { span("td-muted") { +(s.clientId?.toString() ?: "—") } }
                                    td { span("td-code") { +(s.ipAddress ?: "—") } }
                                    td { span("td-muted") { +s.createdAt.toDisplayString() } }
                                    td { span("td-muted") { +s.expiresAt.toDisplayString() } }
                                    td {
                                        form(
                                            action = "/admin/workspaces/${workspace.slug}/sessions/${s.id}/revoke",
                                            method = FormMethod.post,
                                            classes = "inline-form",
                                        ) {
                                            button(type = ButtonType.submit, classes = "btn btn-ghost btn-sm") {
                                                +"Revoke"
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

// Audit log.
internal fun auditLogPageImpl(
    workspace: Tenant,
    events: List<AuditEvent>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    page: Int = 1,
    totalPages: Int = 1,
    eventTypeFilter: String? = null,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Audit Log — ${workspace.displayName}",
            activeRail = "logs",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "events",
            loggedInAs = loggedInAs,
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"Audit Log" }
            }
            div("page-header") {
                div {
                    p("page-title") { +"Audit Log" }
                    p("page-subtitle") { +"Security-relevant events for the ${workspace.displayName} workspace." }
                }
            }

            // Filter bar
            form(
                action = "/admin/workspaces/${workspace.slug}/logs",
                method = FormMethod.get,
                classes = "search-form",
            ) {
                div("search-row") {
                    select {
                        name = "event"
                        option {
                            value = ""
                            selected = (eventTypeFilter == null)
                            +"All events"
                        }
                        AuditEventType.entries.forEach { type ->
                            option {
                                value = type.name
                                selected = (type.name == eventTypeFilter)
                                +type.name.lowercase().replace('_', ' ')
                            }
                        }
                    }
                    button(type = ButtonType.submit, classes = "btn btn-sm") { +"Filter" }
                    if (eventTypeFilter != null) {
                        a("/admin/workspaces/${workspace.slug}/logs", classes = "btn btn-ghost btn-sm") {
                            +"Clear"
                        }
                    }
                }
            }

            div("card") {
                if (events.isEmpty()) {
                    div("empty-state") {
                        div("empty-state-icon") { +"⊘" }
                        p("empty-state-text") { +"No events found." }
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Time" }
                                th { +"Event" }
                                th { +"User" }
                                th { +"Client" }
                                th { +"IP" }
                            }
                        }
                        tbody {
                            events.forEach { e ->
                                tr {
                                    td { span("td-muted") { +e.createdAt.toDisplayString() } }
                                    td {
                                        span("td-code") {
                                            style = "font-size:0.75rem;"
                                            +e.eventType.name
                                        }
                                    }
                                    td { span("td-muted") { +(e.userId?.toString() ?: "—") } }
                                    td { span("td-muted") { +(e.clientId?.toString() ?: "—") } }
                                    td { span("td-muted") { +(e.ipAddress ?: "—") } }
                                }
                            }
                        }
                    }
                }
            }

            // Pagination
            if (totalPages > 1) {
                div("pagination") {
                    val baseUrl =
                        "/admin/workspaces/${workspace.slug}/logs" +
                            (if (eventTypeFilter != null) "?event=$eventTypeFilter&" else "?")
                    if (page > 1) {
                        a("${baseUrl}page=${page - 1}", classes = "btn btn-ghost btn-sm") { +"← Prev" }
                    }
                    span("pagination-label") {
                        +"Page $page of $totalPages"
                    }
                    if (page < totalPages) {
                        a("${baseUrl}page=${page + 1}", classes = "btn btn-ghost btn-sm") { +"Next →" }
                    }
                }
            }
        }
    }
