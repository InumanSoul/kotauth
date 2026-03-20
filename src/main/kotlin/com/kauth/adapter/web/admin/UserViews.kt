package com.kauth.adapter.web.admin

import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import kotlinx.html.*

internal fun userDetailPageImpl(
    workspace: Tenant,
    user: User,
    sessions: List<Session>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    successMessage: String? = null,
    editError: String? = null,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "${user.username} — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "users",
            loggedInAs = loggedInAs,
        ) {
            breadcrumb(
                "Workspaces" to "/admin",
                workspace.slug to "/admin/workspaces/${workspace.slug}",
                "Users" to "/admin/workspaces/${workspace.slug}/users",
                user.username to null,
            )

            // ── User header with avatar ──────────────────────────────
            div("user-header") {
                div("user-header__left") {
                    div("user-header__avatar") {
                        +userInitials(user.fullName, user.username)
                    }
                    div {
                        div("user-header__name") { +user.fullName.ifBlank { user.username } }
                        div("user-header__meta") {
                            span("badge badge--id-muted") { +user.username }
                            if (user.enabled) {
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
                            if (!user.emailVerified) {
                                span("badge badge--inactive") { +"Email unverified" }
                            }
                        }
                    }
                }
                div("user-header__actions") {
                    if (workspace.isSmtpReady) {
                        postButton(
                            action = "/admin/workspaces/${workspace.slug}/users/${user.id}/send-reset-email",
                            label = "Send Reset Email",
                            btnClass = "btn btn--ghost btn--sm",
                        )
                    }
                    // Toggle to edit mode — inline JS
                    button(classes = "btn btn--ghost btn--sm") {
                        attributes["onclick"] = "toggleEdit()"
                        +"Edit Profile"
                    }
                }
            }

            // ── Alerts ───────────────────────────────────────────────
            if (successMessage != null) {
                div("notice") {
                    span("notice__icon") { inlineSvgIcon("warning", "warning") }
                    div("notice__body") {
                        span("notice__title") { +successMessage }
                    }
                }
            }
            if (editError != null) {
                div("alert alert-error") {
                    style = "margin-bottom:20px;"
                    +editError
                }
            }

            // ── Profile (read mode) ──────────────────────────────────
            div("section") {
                id = "read-section"
                div("section__header") {
                    span("section__title") { +"Profile" }
                }
                ovCard {
                    div("ov-card__row") {
                        span("ov-card__label") { +"Username" }
                        span("ov-card__value") {
                            span("ov-card__value--mono") { +user.username }
                            copyBtn(user.username)
                            span("lock-icon") {
                                attributes["title"] = "Immutable after creation"
                                +"\uD83D\uDD12"
                            }
                        }
                    }
                    ovRow("Email") {
                        +user.email
                        copyBtn(user.email)
                    }
                    ovRowText("Full Name", user.fullName.ifBlank { "—" })
                    ovRowMuted("Member since", "—")
                }
            }

            // ── Profile (edit mode, hidden by default) ───────────────
            div("section") {
                id = "edit-section"
                style = "display:none;"
                div("section__header") {
                    span("section__title") { +"Edit Profile" }
                }
                div("ov-card") {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/users/${user.id}/edit",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        div("edit-row") {
                            span("edit-row__label") { +"Username" }
                            div {
                                input(classes = "edit-row__field") {
                                    type = InputType.text
                                    value = user.username
                                    disabled = true
                                }
                                div("edit-row__hint") { +"Immutable after creation" }
                            }
                        }
                        div("edit-row") {
                            span("edit-row__label") { +"Email" }
                            input(classes = "edit-row__field") {
                                type = InputType.email
                                name = "email"
                                value = user.email
                            }
                        }
                        div("edit-row") {
                            span("edit-row__label") { +"Full Name" }
                            input(classes = "edit-row__field") {
                                type = InputType.text
                                name = "fullName"
                                value = user.fullName
                            }
                        }
                        div("edit-actions") {
                            button(type = ButtonType.submit, classes = "btn btn--primary btn--sm") {
                                +"Save changes"
                            }
                            button(classes = "btn btn--ghost btn--sm") {
                                type = ButtonType.button
                                attributes["onclick"] = "toggleEdit()"
                                +"Cancel"
                            }
                        }
                    }
                }
            }

            // ── Active Sessions ──────────────────────────────────────
            section(
                title = "Active Sessions",
                action = {
                    div {
                        style = "display:flex;align-items:center;gap:10px;"
                        span {
                            style = "font-size:11px;color:var(--color-subtle);"
                            +"${sessions.size} session${if (sessions.size != 1) "s" else ""}"
                        }
                        if (sessions.isNotEmpty()) {
                            postButton(
                                action = "/admin/workspaces/${workspace.slug}/users/${user.id}/revoke-sessions",
                                label = "Revoke all",
                                btnClass = "btn btn--warning btn--sm",
                            )
                        } else {
                            button(classes = "btn btn--warning btn--sm") {
                                disabled = true
                                style = "opacity:0.35;cursor:not-allowed;"
                                +"Revoke all"
                            }
                        }
                    }
                },
            ) {
                if (sessions.isEmpty()) {
                    emptyState(
                        iconName = "lock",
                        title = "No active sessions",
                        description = "This user hasn't logged into any application yet.",
                    )
                } else {
                    table("data-table") {
                        thead {
                            tr {
                                th { +"Created" }
                                th { +"Expires" }
                                th { +"IP Address" }
                                th { style = "width:80px;" }
                            }
                        }
                        tbody {
                            sessions.forEach { s ->
                                tr {
                                    td { +s.createdAt.toDisplayString() }
                                    td { +s.expiresAt.toDisplayString() }
                                    td {
                                        span("ov-card__value--mono") { +(s.ipAddress ?: "—") }
                                    }
                                    td {
                                        div("data-table__actions") {
                                            postButton(
                                                action = "/admin/workspaces/${workspace.slug}/sessions/${s.id}/revoke",
                                                label = "Revoke",
                                                btnClass = "btn btn--ghost btn--sm",
                                            )
                                        }
                                    }
                                }
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
                        title = "Disable this user",
                        description = "Blocks all login attempts. Account data is preserved and this can be reversed.",
                    ) {
                        postButton(
                            action = "/admin/workspaces/${workspace.slug}/users/${user.id}/toggle",
                            label = if (user.enabled) "Disable" else "Enable",
                            btnClass = "btn btn--danger btn--sm",
                        )
                    }
                }
            }

            // ── Toggle script ────────────────────────────────────────
            script {
                unsafe {
                    +
"""
function toggleEdit() {
    var r = document.getElementById('read-section');
    var e = document.getElementById('edit-section');
    var hidden = e.style.display === 'none';
    r.style.display = hidden ? 'none' : 'block';
    e.style.display = hidden ? 'block' : 'none';
}
"""
                }
            }
        }
    }

// ─── Private helpers ────────────────────────────────────────────────────────

private fun userInitials(
    fullName: String,
    username: String,
): String {
    if (fullName.isBlank()) return username.take(2).uppercase()
    val parts = fullName.trim().split("\\s+".toRegex())
    return if (parts.size >= 2) {
        "${parts.first().first().uppercaseChar()}${parts.last().first().uppercaseChar()}"
    } else {
        fullName.take(2).uppercase()
    }
}

/**
 * Holds create-user form values for prefill after a failed submission.
 */
data class UserPrefill(
    val username: String = "",
    val email: String = "",
    val fullName: String = "",
)

// User list page.
internal fun userListPageImpl(
    workspace: Tenant,
    users: List<User>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    search: String? = null,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Users — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "users",
            loggedInAs = loggedInAs,
        ) {
            breadcrumb(
                "Workspaces" to "/admin",
                workspace.slug to "/admin/workspaces/${workspace.slug}",
                "Users" to null,
            )

            // ── Page header ──────────────────────────────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Users" }
                        span("page-header__sub") {
                            +"${users.size} user${if (users.size != 1) "s" else ""} in this workspace"
                        }
                    }
                }
                div("page-header__actions") {
                    primaryLink(
                        "/admin/workspaces/${workspace.slug}/users/new",
                        "New User",
                        "plus",
                    )
                }
            }

            // ── Search bar (server-side GET) ─────────────────────────
            form(
                action = "/admin/workspaces/${workspace.slug}/users",
                method = FormMethod.get,
            ) {
                div("filter-bar") {
                    inlineSvgIcon("search", "search", cssClass = "filter-bar__icon")
                    input(type = InputType.search, name = "q", classes = "filter-bar__input") {
                        placeholder = "Filter by username, email, or name…"
                        value = search ?: ""
                        attributes["onchange"] = "this.form.submit()"
                    }
                }
            }

            // ── Users table ──────────────────────────────────────────
            if (users.isEmpty()) {
                emptyState(
                    iconName = "user",
                    title = if (search != null) "No users found" else "No users yet",
                    description = if (search != null) {
                        "No users match \"$search\". Try a different username, email, or name."
                    } else {
                        "Create a user to get started."
                    },
                    cta = if (search != null) {
                        {
                            a(
                                href = "/admin/workspaces/${workspace.slug}/users",
                                classes = "empty-state__cta",
                            ) { +"Clear filter" }
                        }
                    } else {
                        null
                    },
                )
            } else {
                table("data-table") {
                    thead {
                        tr {
                            th { style = "width:200px;"; +"Username" }
                            th { +"Full Name" }
                            th { +"Email" }
                            th { style = "width:110px;"; +"Status" }
                            th { style = "width:70px;" }
                        }
                    }
                    tbody {
                        users.forEach { user ->
                            tr {
                                td {
                                    a(
                                        href = "/admin/workspaces/${workspace.slug}/users/${user.id}",
                                        classes = "data-table__id",
                                    ) { +user.username }
                                }
                                td {
                                    span("data-table__name") { +user.fullName }
                                }
                                td {
                                    span("data-table__email") { +user.email }
                                }
                                td {
                                    if (user.enabled) {
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
                                td {
                                    div("data-table__actions") {
                                        a(
                                            href = "/admin/workspaces/${workspace.slug}/users/${user.id}",
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

// Create user form.
internal fun createUserPageImpl(
    workspace: Tenant,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    error: String? = null,
    prefill: UserPrefill = UserPrefill(),
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "New User — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "users",
            loggedInAs = loggedInAs,
        ) {
            breadcrumb(
                "Workspaces" to "/admin",
                workspace.slug to "/admin/workspaces/${workspace.slug}",
                "Users" to "/admin/workspaces/${workspace.slug}/users",
                "New User" to null,
            )

            // ── Page header (narrow form variant) ────────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Create User" }
                        p("page-header__sub") {
                            +"Add a user to the "
                            strong { +workspace.displayName }
                            +" workspace. The account will be pre-verified."
                        }
                    }
                }
            }

            if (error != null) {
                div("alert alert-error") {
                    style = "margin-bottom:20px;"
                    +error
                }
            }

            // ── Form ─────────────────────────────────────────────────
            div("ov-card") {
                form(
                    action = "/admin/workspaces/${workspace.slug}/users",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    div("edit-row") {
                        span("edit-row__label") { +"Username" }
                        div {
                            input(classes = "edit-row__field edit-row__field--mono") {
                                type = InputType.text
                                name = "username"
                                required = true
                                value = prefill.username
                                placeholder = "johndoe"
                                autoComplete = false
                                attributes["spellcheck"] = "false"
                                attributes["pattern"] = "[a-zA-Z0-9._-]+"
                            }
                            div("edit-row__hint") {
                                +"Letters, digits, dots, underscores, hyphens. Immutable after creation."
                            }
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Email" }
                        input(classes = "edit-row__field") {
                            type = InputType.email
                            name = "email"
                            required = true
                            value = prefill.email
                            placeholder = "john@example.com"
                            autoComplete = false
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Full Name" }
                        input(classes = "edit-row__field") {
                            type = InputType.text
                            name = "fullName"
                            value = prefill.fullName
                            placeholder = "John Doe"
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Password" }
                        div {
                            input(classes = "edit-row__field") {
                                type = InputType.password
                                name = "password"
                                required = true
                                placeholder = "Minimum 8 characters"
                            }
                            div("edit-row__hint") { +"The user can change it after login." }
                        }
                    }
                    div("edit-actions") {
                        button(type = ButtonType.submit, classes = "btn btn--primary") { +"Create User" }
                        a(
                            href = "/admin/workspaces/${workspace.slug}/users",
                            classes = "btn btn--ghost",
                        ) { +"Cancel" }
                    }
                }
            }
        }
    }
