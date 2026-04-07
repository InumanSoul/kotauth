package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.Group
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Role
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import kotlinx.html.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal fun userDetailPageImpl(
    workspace: Tenant,
    user: User,
    sessions: List<Session>,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    successMessage: String? = null,
    editError: String? = null,
    roles: List<Role> = emptyList(),
    groups: List<Group> = emptyList(),
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "${user.username} — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "users",
            loggedInAs = loggedInAs,
                    contentClass = "content-outer",
            toastMessage = successMessage,
) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                workspace.slug to "/admin/workspaces/${workspace.slug}",
                "Users" to "/admin/workspaces/${workspace.slug}/users",
                user.username to null,
            )
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
                            if (user.isLocked) {
                                span("badge badge--warn") {
                                    span("badge__dot") {}
                                    +"Locked"
                                }
                            }
                            if (RequiredAction.SET_PASSWORD in user.requiredActions) {
                                span("badge badge--warn") {
                                    span("badge__dot") {}
                                    +EnglishStrings.BADGE_INVITE_PENDING
                                }
                            }
                        }
                    }
                }
                div("user-header__actions") {
                    if (workspace.isSmtpReady) {
                        postButton(
                            action = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/send-reset-email",
                            label = "Send Reset Email",
                            btnClass = "btn btn--ghost",
                        )
                    } else {
                        span("tooltip-wrap") {
                            attributes["data-tooltip"] = "Configure SMTP to enable password reset emails"
                            button(classes = "btn btn--ghost") {
                                disabled = true
                                +"Send Reset Email"
                            }
                        }
                    }
                    button(classes = "btn btn--ghost") {
                        attributes["hx-get"] =
                            "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/edit-fragment"
                        attributes["hx-target"] = "#profile-section"
                        attributes["hx-swap"] = "outerHTML"
                        +"Edit Profile"
                    }
                }
            }

            if (editError != null) {
                div("notice notice--error") { +editError }
            }
            if (user.isLocked) {
                notice(
                    title = "Account temporarily locked",
                    description = "Locked due to repeated failed login attempts. The account will auto-unlock after the lockout period expires, or you can unlock it immediately.",
                )
            }

            // ── Profile (read mode — swapped via htmx) ──────────────
            userProfileReadFragment(user, roles = roles, groups = groups)

            // ── Active Sessions ──────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") {
                    span { +"Active Sessions" }
                    div {
                        style = "display:flex;align-items:center;gap:10px;"
                        span {
                            style = "font-size:11px;color:var(--color-subtle);text-transform:none;letter-spacing:normal;"
                            +"${sessions.size} session${if (sessions.size != 1) "s" else ""}"
                        }
                        if (sessions.isNotEmpty()) {
                            postButton(
                                action = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/revoke-sessions",
                                label = "Revoke all",
                                btnClass = "btn btn--warning btn--sm",
                                confirmMessage = "Revoke all active sessions for this user? They will be signed out everywhere.",
                            )
                        } else {
                            button(classes = "btn btn--warning btn--sm") {
                                disabled = true
                                title = "No active sessions to revoke"
                                +"Revoke all"
                            }
                        }
                    }
                }
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
                                                action = "/admin/workspaces/${workspace.slug}/sessions/${s.id?.value}/revoke",
                                                label = "Revoke",
                                                btnClass = "btn btn--ghost btn--sm",
                                                confirmMessage = "Revoke this session? The user will be signed out.",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Danger zone ──────────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label ov-card__section-label--danger") { +"Danger zone" }
                div("danger-zone") {
                    if (user.isLocked) {
                        dangerZoneCard(
                            title = "Unlock this account",
                            description = "Reset the failed login counter and allow the user to log in immediately.",
                        ) {
                            postButton(
                                action = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/unlock",
                                label = "Unlock",
                                btnClass = "btn btn--primary btn--sm",
                            )
                        }
                    }
                    dangerZoneCard(
                        title = if (user.enabled) "Disable this user" else "Enable this user",
                        description = if (user.enabled) {
                            "Blocks all login attempts. Account data is preserved and this can be reversed."
                        } else {
                            "This user is currently disabled. Re-enable to allow login."
                        },
                    ) {
                        postButton(
                            action = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/toggle",
                            label = if (user.enabled) "Disable" else "Enable",
                            btnClass = "btn btn--danger btn--sm",
                            confirmMessage = if (user.enabled) {
                                "Disable this user? They will be unable to log in until re-enabled."
                            } else {
                                null
                            },
                        )
                    }
                }
            }
                    }
}
    }

// ─── htmx fragments ────────────────────────────────────────────────────────

/**
 * Profile read-only section — rendered as a swappable fragment.
 * Used both in the full page and returned standalone for htmx swaps.
 */
internal fun DIV.userProfileReadFragment(
    user: User,
    successMessage: String? = null,
    roles: List<Role> = emptyList(),
    groups: List<Group> = emptyList(),
) {
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

    div {
        id = "profile-section"
        if (successMessage != null) {
            div("notice notice--success") {
                style = "margin-bottom:12px;"
                span("notice__icon") { inlineSvgIcon("check-circle", "Success") }
                div("notice__body") {
                    span("notice__title") { +successMessage }
                }
            }
        }
        div("ov-card") {
            div("ov-card__section-label") { +"Profile" }
            div("ov-card__row") {
                span("ov-card__label") { +"Username" }
                span("ov-card__value") {
                    span("ov-card__value--mono") { +user.username }
                    copyBtn(user.username)
                    span("lock-icon") {
                        attributes["title"] = "Immutable after creation"
                        inlineSvgIcon("lock", "Immutable")
                    }
                }
            }
            ovRow("Email") {
                +user.email
                copyBtn(user.email)
            }
            ovRowText("Full Name", user.fullName.ifBlank { "—" })

            val memberSince = user.createdAt
                ?.atOffset(ZoneOffset.UTC)
                ?.format(dateFormatter)
                ?: "—"
            ovRowMuted("Member since", memberSince)

            if (roles.isNotEmpty()) {
                div("ov-card__row") {
                    span("ov-card__label") { +"Roles" }
                    span("ov-card__value") {
                        style = "display:flex;flex-wrap:wrap;gap:6px;"
                        for (role in roles) {
                            span("badge badge--id-muted") { +role.name }
                        }
                    }
                }
            }

            if (groups.isNotEmpty()) {
                div("ov-card__row") {
                    span("ov-card__label") { +"Groups" }
                    span("ov-card__value") {
                        style = "display:flex;flex-wrap:wrap;gap:6px;"
                        for (group in groups) {
                            span("badge badge--id-muted") { +group.name }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Profile edit form section — returned as a standalone fragment for htmx swap.
 * Replaces #profile-section when the user clicks "Edit Profile".
 */
internal fun DIV.userProfileEditFragment(
    workspace: Tenant,
    user: User,
    editError: String? = null,
) {
    div {
        id = "profile-section"
        if (editError != null) {
            div("notice notice--error") { +editError }
        }
        div("ov-card") {
            div("ov-card__section-label") { +"Edit Profile" }
            form(
                action = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/edit",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                attributes["hx-post"] = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/edit"
                attributes["hx-target"] = "#profile-section"
                attributes["hx-swap"] = "outerHTML"
                div("edit-row") {
                    span("edit-row__label") { +"Username" }
                    div {
                        input(classes = "edit-row__field edit-row__field--mono") {
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
                        attributes["hx-get"] =
                            "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/profile-fragment"
                        attributes["hx-target"] = "#profile-section"
                        attributes["hx-swap"] = "outerHTML"
                        +"Cancel"
                    }
                }
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
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    search: String? = null,
    page: Int = 1,
    totalPages: Int = 1,
    totalCount: Long = 0,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Users — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "users",
            loggedInAs = loggedInAs,
                  contentClass = "content-outer",
) {
            div("content-inner") {
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

            // ── Search bar (server-side GET, htmx-enhanced) ──────────
            form(
                action = "/admin/workspaces/${workspace.slug}/users",
                method = FormMethod.get,
            ) {
                div("filter-bar") {
                    inlineSvgIcon("search", "search", cssClass = "filter-bar__icon")
                    input(type = InputType.search, name = "q", classes = "filter-bar__input") {
                        placeholder = "Filter by username, email, or name…"
                        value = search ?: ""
                        attributes["hx-get"] = "/admin/workspaces/${workspace.slug}/users"
                        attributes["hx-target"] = "#user-list-content"
                        attributes["hx-trigger"] = "input changed delay:300ms, search"
                        attributes["hx-replace-url"] = "true"
                        attributes["hx-select"] = "#user-list-content"
                        attributes["hx-indicator"] = ".htmx-loader"
                    }
                    span("htmx-loader") { +"Loading…" }
                }
            }

            // ── Users table (htmx swap target) ───────────────────────
            div {
                id = "user-list-content"

                span("page-header__sub") {
                    val suffix = if (totalCount != 1L) "s" else ""
                    if (search != null) {
                        if (totalPages > 1) {
                            val start = (page - 1) * users.size.coerceAtLeast(1) + 1
                            val end = start + users.size - 1
                            +"Showing $start\u2013$end of $totalCount result$suffix for \u201c$search\u201d"
                        } else {
                            +"$totalCount result$suffix for \u201c$search\u201d"
                        }
                    } else if (totalPages > 1) {
                        val start = (page - 1) * users.size.coerceAtLeast(1) + 1
                        val end = start + users.size - 1
                        +"Showing $start\u2013$end of $totalCount user$suffix"
                    } else {
                        +"$totalCount user$suffix in this workspace"
                    }
                }

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
                            id = "user-table-body"
                            users.forEach { user ->
                                tr {
                                    td {
                                        a(
                                            href = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}",
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
                                        if (!user.enabled) {
                                            span("badge badge--inactive") {
                                                span("badge__dot") {}
                                                +"Disabled"
                                            }
                                        } else if (user.isLocked) {
                                            span("badge badge--warn") {
                                                span("badge__dot") {}
                                                +"Locked"
                                            }
                                        } else {
                                            span("badge badge--active") {
                                                span("badge__dot") {}
                                                +"Active"
                                            }
                                        }
                                    }
                                    td {
                                        div("data-table__actions") {
                                            a(
                                                href = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}",
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

                paginationControls(
                    currentPage = page,
                    totalPages = totalPages,
                    baseUrl = "/admin/workspaces/${workspace.slug}/users" +
                        if (search != null) "?q=$search&" else "?",
                    htmxTarget = "#user-list-content",
                )
            }
                    }
}
    }

// Create user form.
internal fun createUserPageImpl(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
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
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "users",
            loggedInAs = loggedInAs,
                    contentClass = "content-outer",
) {
            div("content-inner") {
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
                            +" workspace."
                        }
                    }
                }
            }

            if (error != null) {
                div("notice notice--error") { +error }
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
                    // ── Credential setup radio ──────────────────
                    div("edit-row") {
                        span("edit-row__label") { +"Credential setup" }
                        div("radio-group") {
                            label("radio-option") {
                                input(type = InputType.radio, name = "setupMode") {
                                    value = "invite"
                                    id = "setupMode_invite"
                                    if (workspace.isSmtpReady) {
                                        checked = true
                                    } else {
                                        disabled = true
                                    }
                                    attributes["data-setup-toggle"] = "invite"
                                }
                                span { +EnglishStrings.INVITE_RADIO_SEND }
                                if (workspace.isSmtpReady) {
                                    span("edit-row__hint") { +EnglishStrings.INVITE_RADIO_SEND_HINT }
                                } else {
                                    span("edit-row__hint edit-row__hint--disabled") {
                                        +EnglishStrings.INVITE_RADIO_SMTP_HINT
                                    }
                                }
                            }
                            label("radio-option") {
                                input(type = InputType.radio, name = "setupMode") {
                                    value = "password"
                                    id = "setupMode_password"
                                    if (!workspace.isSmtpReady) checked = true
                                    attributes["data-setup-toggle"] = "password"
                                }
                                span { +EnglishStrings.INVITE_RADIO_PASSWORD }
                            }
                        }
                    }
                    // ── Password field (hidden when invite selected) ────
                    div("edit-row") {
                        id = "passwordField"
                        if (workspace.isSmtpReady) {
                            style = "display:none;"
                        }
                        span("edit-row__label") { +EnglishStrings.PASSWORD }
                        div {
                            input(classes = "edit-row__field") {
                                type = InputType.password
                                name = "password"
                                placeholder =
                                    EnglishStrings.passwordMinPlaceholder(workspace.securityConfig.passwordMinLength)
                                attributes["data-pw-min-length"] =
                                    workspace.securityConfig.passwordMinLength.toString()
                                if (workspace.securityConfig.passwordRequireUppercase) {
                                    attributes["data-pw-require-upper"] = "true"
                                }
                                if (workspace.securityConfig.passwordRequireNumber) {
                                    attributes["data-pw-require-number"] = "true"
                                }
                                if (workspace.securityConfig.passwordRequireSpecial) {
                                    attributes["data-pw-require-special"] = "true"
                                }
                            }
                            div("edit-row__hint") { +EnglishStrings.PASSWORD_HINT_USER_CAN_CHANGE }
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
    }
