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
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    userMap: Map<UserId, String> = emptyMap(),
    clientMap: Map<ApplicationId, String> = emptyMap(),
    savedParam: String? = null,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Sessions — ${workspace.displayName}",
            activeRail = "security",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "sessions",
            loggedInAs = loggedInAs,
            contentClass = "content-outer",
            toastMessage = when (savedParam) {
                "revoked" -> "Session revoked."
                "revoked_all" -> "All sessions revoked."
                else -> null
            },
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    workspace.slug to "/admin/workspaces/${workspace.slug}",
                    "Security" to "/admin/workspaces/${workspace.slug}/sessions",
                    "Sessions" to null,
                )

                pageHeader(
                    title = "Active Sessions",
                    subtitle = "${sessions.size} active session${if (sessions.size != 1) "s" else ""} in this workspace",
                    actions = if (sessions.isNotEmpty()) {
                        {
                            postButton(
                                action = "/admin/workspaces/${workspace.slug}/sessions/revoke-all",
                                label = "Revoke All Sessions",
                                btnClass = "btn btn--warning btn--sm",
                                confirmMessage = "Revoke all ${sessions.size} active session${if (sessions.size != 1) "s" else ""}? All users will be signed out immediately.",
                            )
                        }
                    } else {
                        null
                    },
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
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    page: Int = 1,
    totalPages: Int = 1,
    eventTypeFilter: String? = null,
    userMap: Map<UserId, String> = emptyMap(),
    clientMap: Map<ApplicationId, String> = emptyMap(),
    clientLinks: Map<ApplicationId, ClientDisplayInfo> = emptyMap(),
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Audit Log — ${workspace.displayName}",
            activeRail = "logs",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "audit",
            loggedInAs = loggedInAs,
            showSidebar = false,
            contentClass = "content-outer",
        ) {
            div("content-inner content-inner--wide") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    workspace.slug to "/admin/workspaces/${workspace.slug}",
                    "Logs" to "/admin/workspaces/${workspace.slug}/logs",
                    "Audit Log" to null,
                )

                pageHeader(
                    title = "Audit Log",
                    subtitle = "Security-relevant events for the ${workspace.displayName} workspace.",
                )

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
                            attributes["hx-get"] = "/admin/workspaces/${workspace.slug}/logs"
                            attributes["hx-target"] = "#audit-content"
                            attributes["hx-select"] = "#audit-content"
                            attributes["hx-push-url"] = "true"
                            attributes["hx-indicator"] = ".htmx-loader"
                            attributes["hx-trigger"] = "change"
                            option {
                                value = ""
                                selected = (eventTypeFilter == null)
                                +"All events"
                            }
                            val groups = linkedMapOf(
                                "Login & Registration" to listOf("LOGIN_", "REGISTER_", "ACCOUNT_"),
                                "Tokens & Authorization" to listOf("TOKEN_", "AUTHORIZATION_CODE_"),
                                "Sessions" to listOf("SESSION_"),
                                "Admin Actions" to listOf("ADMIN_"),
                                "Email & Password" to listOf("EMAIL_", "PASSWORD_"),
                                "User Self-Service" to listOf("USER_"),
                                "MFA" to listOf("MFA_"),
                            )
                            groups.forEach { (groupLabel, prefixes) ->
                                val types = AuditEventType.entries.filter { t ->
                                    prefixes.any { t.name.startsWith(it) }
                                }
                                if (types.isNotEmpty()) {
                                    optGroup(groupLabel) {
                                        types.forEach { type ->
                                            option {
                                                value = type.name
                                                selected = (type.name == eventTypeFilter)
                                                +type.name.lowercase().replace('_', ' ')
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn btn--ghost") { +"Filter" }
                        if (eventTypeFilter != null) {
                            a(
                                "/admin/workspaces/${workspace.slug}/logs",
                                classes = "btn btn--ghost",
                            ) {
                                +"Clear"
                            }
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
                                            td {
                                                span("badge ${e.eventType.badgeModifier()}") {
                                                    +e.eventType.name.lowercase().replace('_', ' ')
                                                }
                                            }
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
                                                    val info = clientLinks[cid]
                                                    if (info != null) {
                                                        a(href = "/admin/workspaces/${workspace.slug}/applications/${info.clientId}") {
                                                            +info.name
                                                        }
                                                    } else {
                                                        +(clientMap[cid] ?: cid.value.toString())
                                                    }
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

private fun AuditEventType.badgeModifier(): String =
    when (this) {
        AuditEventType.LOGIN_SUCCESS,
        AuditEventType.REGISTER_SUCCESS,
        AuditEventType.TOKEN_ISSUED,
        AuditEventType.TOKEN_REFRESHED,
        AuditEventType.AUTHORIZATION_CODE_ISSUED,
        AuditEventType.AUTHORIZATION_CODE_USED,
        AuditEventType.SESSION_CREATED,
        AuditEventType.EMAIL_VERIFIED,
        AuditEventType.PASSWORD_RESET_COMPLETED,
        AuditEventType.USER_PROFILE_UPDATED,
        AuditEventType.USER_PASSWORD_CHANGED,
        AuditEventType.MFA_ENROLLMENT_VERIFIED,
        AuditEventType.MFA_CHALLENGE_SUCCESS,
        AuditEventType.ADMIN_USER_ENABLED,
        AuditEventType.ADMIN_CLIENT_ENABLED,
        AuditEventType.ADMIN_USER_PASSWORD_RESET,
        AuditEventType.ACCOUNT_UNLOCKED,
        -> "badge--active"

        AuditEventType.LOGIN_FAILED,
        AuditEventType.REGISTER_FAILED,
        AuditEventType.MFA_CHALLENGE_FAILED,
        AuditEventType.ACCOUNT_LOCKED,
        AuditEventType.ADMIN_USER_DISABLED,
        AuditEventType.ADMIN_CLIENT_DISABLED,
        AuditEventType.USER_ACCOUNT_DISABLED_SELF,
        -> "badge--danger"

        AuditEventType.LOGIN_RATE_LIMITED,
        AuditEventType.AUTHORIZATION_CODE_EXPIRED,
        AuditEventType.TOKEN_REVOKED,
        AuditEventType.SESSION_REVOKED,
        AuditEventType.ADMIN_SESSION_REVOKED,
        AuditEventType.ADMIN_SESSIONS_REVOKED_ALL,
        AuditEventType.USER_SESSION_REVOKED_SELF,
        AuditEventType.MFA_DISABLED,
        AuditEventType.MFA_RECOVERY_CODE_USED,
        AuditEventType.ADMIN_CLIENT_SECRET_REGENERATED,
        -> "badge--warn"

        AuditEventType.EMAIL_VERIFICATION_SENT,
        AuditEventType.PASSWORD_RESET_REQUESTED,
        AuditEventType.MFA_ENROLLMENT_STARTED,
        AuditEventType.TOKEN_INTROSPECTED,
        AuditEventType.ADMIN_SMTP_TEST,
        AuditEventType.ADMIN_SMTP_UPDATED,
        -> "badge--info"

        else -> "badge--inactive"
    }
