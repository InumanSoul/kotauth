package com.kauth.adapter.web.admin

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.UserId
import kotlinx.html.*

// Active sessions (workspace-wide).
internal fun activeSessionsPageImpl(
    workspace: Tenant,
    sessions: List<Session>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    userMap: Map<UserId, String> = emptyMap(),
    clientMap: Map<ApplicationId, String> = emptyMap(),
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
            contentClass = "content-outer",
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    workspace.slug to "/admin/workspaces/${workspace.slug}",
                    "Security" to "/admin/workspaces/${workspace.slug}/mfa",
                    "Sessions" to null,
                )

                pageHeader(
                    title = "Active Sessions",
                    subtitle = "${sessions.size} active session${if (sessions.size != 1) "s" else ""} in this workspace",
                )

                if (sessions.isEmpty()) {
                    emptyState(
                        iconName = "user",
                        title = "No active sessions",
                        description = "There are no active sessions in this workspace.",
                    )
                } else {
                    table("data-table") {
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
                                    td { span("data-table__id") { +"#${s.id?.value}" } }
                                    td {
                                        val uid = s.userId
                                        if (uid != null) {
                                            val name = userMap[uid] ?: uid.value.toString()
                                            a(href = "/admin/workspaces/${workspace.slug}/users/${uid.value}") { +name }
                                        } else {
                                            +"M2M"
                                        }
                                    }
                                    td {
                                        val cid = s.clientId
                                        if (cid != null) {
                                            +(clientMap[cid] ?: cid.value.toString())
                                        } else {
                                            +"—"
                                        }
                                    }
                                    td { span("data-table__email") { +(s.ipAddress ?: "—") } }
                                    td { +s.createdAt.toDisplayString() }
                                    td { +s.expiresAt.toDisplayString() }
                                    td {
                                        val userName = s.userId?.let { userMap[it] } ?: "this user"
                                        div {
                                            postButton(
                                                action = "/admin/workspaces/${workspace.slug}/sessions/${s.id?.value}/revoke",
                                                label = "Revoke",
                                                btnClass = "btn btn--ghost btn--sm",
                                                confirmMessage = "Revoke session for $userName?",
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

// Audit log.
internal fun auditLogPageImpl(
    workspace: Tenant,
    events: List<AuditEvent>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    page: Int = 1,
    totalPages: Int = 1,
    eventTypeFilter: String? = null,
    userMap: Map<UserId, String> = emptyMap(),
    clientMap: Map<ApplicationId, String> = emptyMap(),
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
            showSidebar = false,
            contentClass = "content-outer",
        ) {
            div("content-inner content-inner--wide") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    workspace.slug to "/admin/workspaces/${workspace.slug}",
                    "Audit Log" to null,
                )

                div("page-header") {
                    div("page-header__left") {
                        div("page-header__identity") {
                            h1("page-header__title") { +"Audit Log" }
                            p("page-header__sub") {
                                +"Security-relevant events for the "
                                strong { +workspace.displayName }
                                +" workspace."
                            }
                        }
                    }
                }

                div {
                    id = "audit-content"

                    // Filter bar — inside the htmx swap target so the Clear button
                    // appears/disappears correctly when filtering via htmx.
                    form(
                        action = "/admin/workspaces/${workspace.slug}/logs",
                        method = FormMethod.get,
                        classes = "filter-bar filter-bar--row",
                    ) {
                        attributes["hx-get"] = "/admin/workspaces/${workspace.slug}/logs"
                        attributes["hx-target"] = "#audit-content"
                        attributes["hx-select"] = "#audit-content"
                        attributes["hx-push-url"] = "true"
                        attributes["hx-indicator"] = ".htmx-loader"
                        select("filter-bar__select") {
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
                        button(type = ButtonType.submit, classes = "btn btn--sm") { +"Filter" }
                        if (eventTypeFilter != null) {
                            a(
                                "/admin/workspaces/${workspace.slug}/logs",
                                classes = "btn btn--ghost btn--sm",
                            ) { +"Clear" }
                        }
                    }

                        // Data table
                        if (events.isEmpty()) {
                            emptyState(
                                iconName = "search",
                                title = "No events found",
                                description =
                                    if (eventTypeFilter != null) {
                                        "No events match the selected filter. Try clearing the filter."
                                    } else {
                                        "No audit events have been recorded for this workspace yet."
                                    },
                            )
                        } else {
                            table("data-table") {
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
                                            td { +e.createdAt.toDisplayString() }
                                            td { span("data-table__id") { +e.eventType.name } }
                                            td {
                                                val uid = e.userId
                                                if (uid != null) {
                                                    val name = userMap[uid] ?: uid.value.toString()
                                                    a(href = "/admin/workspaces/${workspace.slug}/users/${uid.value}") { +name }
                                                } else {
                                                    +"—"
                                                }
                                            }
                                            td {
                                                val cid = e.clientId
                                                if (cid != null) {
                                                    +(clientMap[cid] ?: cid.value.toString())
                                                } else {
                                                    +"—"
                                                }
                                            }
                                            td {
                                                span("data-table__email") { +(e.ipAddress ?: "—") }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Pagination
                        if (totalPages > 1) {
                            div("data-table-pagination") {
                                val baseUrl =
                                    "/admin/workspaces/${workspace.slug}/logs" +
                                        (if (eventTypeFilter != null) "?event=$eventTypeFilter&" else "?")
                                if (page > 1) {
                                    val prevUrl = "${baseUrl}page=${page - 1}"
                                    a(prevUrl, classes = "btn btn--ghost btn--sm") {
                                        attributes["hx-get"] = prevUrl
                                        attributes["hx-target"] = "#audit-content"
                                        attributes["hx-select"] = "#audit-content"
                                        attributes["hx-push-url"] = "true"
                                        +"← Prev"
                                    }
                                }
                                span("data-table-pagination__label") {
                                    +"Page $page of $totalPages"
                                }
                                if (page < totalPages) {
                                    val nextUrl = "${baseUrl}page=${page + 1}"
                                    a(nextUrl, classes = "btn btn--ghost btn--sm") {
                                        attributes["hx-get"] = nextUrl
                                        attributes["hx-target"] = "#audit-content"
                                        attributes["hx-select"] = "#audit-content"
                                        attributes["hx-push-url"] = "true"
                                        +"Next →"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
