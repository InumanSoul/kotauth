package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.adapter.webauthn.AaguidLookup
import com.kauth.domain.model.Group
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Role
import com.kauth.domain.model.Session
import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import com.kauth.domain.model.WebAuthnCredential
import io.ktor.http.encodeURLParameter
import kotlinx.html.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The impersonation this admin session currently holds, if any.
 *
 * Starting a second impersonation revokes the first, which the route has always done and the
 * page could never say, because nothing told it one was running. Tracking it lets the button
 * warn before it clobbers, and gives the admin somewhere to end a session deliberately rather
 * than by starting another.
 */
data class ActiveImpersonation(
    val targetUserId: Int,
    val targetUsername: String,
    val targetWorkspaceSlug: String,
    val sessionId: Int,
    val startedAt: Instant,
)

/** One row in the user-detail "Recent impersonations" panel. */
data class ImpersonationRecord(
    val adminUsername: String,
    val startedAt: Instant,
)

data class OtpActivityRecord(
    val eventType: String,
    val ipAddress: String?,
    val reason: String?,
    val occurredAt: Instant,
)

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
    userAttributes: Map<String, String> = emptyMap(),
    /** attribute_key -> claim_name for keys that have a mapper configured. */
    mappedKeys: Map<String, String> = emptyMap(),
    attributeError: String? = null,
    /**
     * One-time reveal of a forced-change-password link. Shown once when the
     * admin has just clicked "Set temporary password"; on refresh the flash
     * slot is empty and the panel is gone.
     */
    tempPasswordLink: String? = null,
    recentImpersonations: List<ImpersonationRecord> = emptyList(),
    recentOtpActivity: List<OtpActivityRecord> = emptyList(),
    passkeys: List<WebAuthnCredential> = emptyList(),
    /**
     * True when the broker created this account on a first sign-in. It is not derivable from the
     * user row: a just-in-time account carries no `externalId` — its provider link lives in
     * `social_accounts`, which a locally registered account acquires too the first time it signs
     * in through a provider. The provisioning event is the only record of which one created it.
     */
    brokeredOrigin: Boolean = false,
    linkedIdentities: List<SocialAccount> = emptyList(),
    activeImpersonation: ActiveImpersonation? = null,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "${user.username} · ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "users",
            loggedInAs = loggedInAs,
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
                            when {
                                user.externalId != null -> idpManagedBadge()
                                brokeredOrigin -> idpManagedBadge(EnglishStrings.IDP_MANAGED_BROKERED_ORIGIN)
                            }
                        }
                    }
                }
                div("user-header__actions") {
                    if (RequiredAction.SET_PASSWORD !in user.requiredActions) {
                        postButton(
                            action =
                                "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/set-temporary-password",
                            label = "Set Temporary Password",
                            btnClass = "btn btn--ghost",
                            confirmMessage =
                                "Force this user to change their password on next login? " +
                                    "A one-time link will be displayed for you to share with them.",
                        )
                    }
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
                    if (!workspace.isMaster) {
                        val impersonateBlockedReason =
                            when {
                                !user.enabled -> EnglishStrings.IMPERSONATE_BLOCKED_DISABLED
                                user.isLocked -> EnglishStrings.IMPERSONATE_BLOCKED_LOCKED
                                RequiredAction.SET_PASSWORD in user.requiredActions ->
                                    EnglishStrings.IMPERSONATE_BLOCKED_PENDING
                                else -> null
                            }
                        if (impersonateBlockedReason == null) {
                            val isThisUser = activeImpersonation?.targetUserId == user.id?.value
                            postButton(
                                action =
                                    "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/impersonate",
                                label = EnglishStrings.IMPERSONATE_BUTTON,
                                btnClass = "btn btn--primary",
                                confirmMessage =
                                    when {
                                        activeImpersonation == null || isThisUser ->
                                            EnglishStrings.IMPERSONATE_CONFIRM
                                        else ->
                                            EnglishStrings.impersonateReplaceConfirm(
                                                activeImpersonation.targetUsername,
                                            )
                                    },
                            )
                        } else {
                            span("tooltip-wrap") {
                                attributes["data-tooltip"] = impersonateBlockedReason
                                button(classes = "btn btn--primary") {
                                    disabled = true
                                    +EnglishStrings.IMPERSONATE_BUTTON
                                }
                            }
                        }
                    }
                }
            }

            if (editError != null) {
                errorNotice(editError)
            }
            if (user.isLocked) {
                notice(
                    title = "Account temporarily locked",
                    description = "Locked due to repeated failed login attempts. The account will auto-unlock after the lockout period expires, or you can unlock it immediately.",
                )
            }

            if (tempPasswordLink != null) {
                notice(modifier = "notice--success", iconName = "check-circle") {
                    div("notice__title") { +"Temporary change-password link generated" }
                    div("notice__desc") {
                        +"Copy it now. It is valid for 24 hours and will be displayed only once."
                    }
                    div("copy-field") {
                        span("copy-field__value") { +tempPasswordLink }
                        button(type = ButtonType.button) {
                            classes = setOf("copy-field__btn")
                            attributes["data-copy"] = tempPasswordLink
                            title = "Copy"
                            inlineSvgIcon("copy", "Copy")
                        }
                    }
                    div("notice__desc") {
                        +"Send this link to the user over a secure channel. "
                        +"The next time they log in normally, they'll also be redirected here."
                    }
                }
            }

            // ── Identity provider ────────────────────────────────────
            // Above the profile card, because the profile is what a sync overwrites.
            user.externalId?.let { idpManagedCard(it) }

            // ── Linked identities ────────────────────────────────────
            linkedIdentitiesCard(linkedIdentities)

            // ── Profile (read mode — swapped via htmx) ──────────────
            userProfileReadFragment(user, roles = roles, groups = groups)

            // ── Custom Attributes ────────────────────────────────────
            userAttributesSection(
                workspace = workspace,
                user = user,
                attributes = userAttributes,
                mappedKeys = mappedKeys,
                error = attributeError,
            )

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

            // ── The impersonation running right now ──────────────────
            activeImpersonation?.let { active -> activeImpersonationCard(active) }

            // ── Recent impersonations ────────────────────────────────
            if (recentImpersonations.isNotEmpty()) {
                div("ov-card") {
                    div("ov-card__section-label") { +"Recent Impersonations" }
                    table("data-table") {
                        thead {
                            tr {
                                th { +"Admin" }
                                th { +"Started" }
                            }
                        }
                        tbody {
                            recentImpersonations.forEach { record ->
                                tr {
                                    td { span("data-table__name") { +record.adminUsername } }
                                    td { +record.startedAt.toDisplayString() }
                                }
                            }
                        }
                    }
                }
            }

            if (recentOtpActivity.isNotEmpty()) {
                div("ov-card") {
                    div("ov-card__section-label") { +"Recent OTP Activity" }
                    table("data-table") {
                        thead {
                            tr {
                                th { +"Event" }
                                th { +"IP" }
                                th { +"Reason" }
                                th { +"When" }
                            }
                        }
                        tbody {
                            recentOtpActivity.forEach { record ->
                                tr {
                                    td {
                                        span("data-table__name") {
                                            +record.eventType.removePrefix("EMAIL_OTP_").lowercase()
                                        }
                                    }
                                    td { +(record.ipAddress ?: "—") }
                                    td { +(record.reason ?: "—") }
                                    td { +record.occurredAt.toDisplayString() }
                                }
                            }
                        }
                    }
                }
            }

            // ── Passkeys ─────────────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") {
                    span { +EnglishStrings.ADMIN_PASSKEYS_HEADING }
                }
                if (passkeys.isEmpty()) {
                    emptyState(
                        iconName = "key",
                        title = "No passkeys enrolled",
                        description = "This user has not registered any passkeys.",
                    )
                } else {
                    table("data-table") {
                        thead {
                            tr {
                                th { +"Name" }
                                th { +"Device" }
                                th { +"Registered" }
                                th { style = "width:80px;" }
                            }
                        }
                        tbody {
                            passkeys.forEach { cred ->
                                tr {
                                    td { span("data-table__name") { +cred.name } }
                                    td { +(AaguidLookup.displayName(cred.aaguid)) }
                                    td { +cred.createdAt.toDisplayString() }
                                    td {
                                        div("data-table__actions") {
                                            postButton(
                                                action = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/passkeys/${cred.id}/revoke",
                                                label = EnglishStrings.PORTAL_PASSKEYS_REVOKE,
                                                btnClass = "btn btn--ghost btn--sm",
                                                confirmMessage = "Revoke this passkey? The user will need to re-register it.",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (passkeys.isNotEmpty()) {
                    div {
                        style = "margin-top:var(--space-3);"
                        postButton(
                            action = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/passkeys/reset-all",
                            label = EnglishStrings.ADMIN_PASSKEYS_RESET_ALL_BUTTON,
                            btnClass = "btn btn--danger btn--sm",
                            confirmMessage = "Remove all passkeys for this user? They will need to re-register.",
                        )
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
                    if (user.mfaEnabled) {
                        dangerZoneCard(
                            title = "Reset MFA",
                            description = "Removes all MFA enrollments and recovery codes for this user. They will need to re-enroll.",
                        ) {
                            postButton(
                                action = "/admin/workspaces/${workspace.slug}/users/${user.id?.value}/mfa/reset",
                                label = EnglishStrings.ADMIN_MFA_RESET_BUTTON,
                                btnClass = "btn btn--danger btn--sm",
                                confirmMessage = "Reset MFA for this user? All enrollments and recovery codes will be removed.",
                            )
                        }
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
            notice(modifier = "notice--success notice--tight", iconName = "check-circle") {
                span("notice__title") { +successMessage }
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
            ovRowText(EnglishStrings.USER_GIVEN_NAME_LABEL, user.givenName ?: "—")
            ovRowText(EnglishStrings.USER_FAMILY_NAME_LABEL, user.familyName ?: "—")

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
    roles: List<Role> = emptyList(),
    groups: List<Group> = emptyList(),
) {
    div {
        id = "profile-section"
        if (editError != null) {
            errorNotice(editError)
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
                namePartRows(givenName = user.givenName ?: "", familyName = user.familyName ?: "")
                readOnlyBadgesRow(
                    label = "Roles",
                    items = roles.map { it.name },
                    manageUrl = "/admin/workspaces/${workspace.slug}/roles",
                )
                readOnlyBadgesRow(
                    label = "Groups",
                    items = groups.map { it.name },
                    manageUrl = "/admin/workspaces/${workspace.slug}/groups",
                )
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

/**
 * The SCIM name parts, rendered identically on the create and edit forms.
 *
 * They sit under Full Name rather than replacing it: the display name is the source of truth and
 * these never rewrite it, which the shared hint says out loud.
 */
private fun FlowContent.namePartRows(
    givenName: String,
    familyName: String,
) {
    div("edit-row") {
        span("edit-row__label") { +EnglishStrings.USER_GIVEN_NAME_LABEL }
        div {
            input(classes = "edit-row__field") {
                type = InputType.text
                name = "givenName"
                value = givenName
                placeholder = "John"
            }
            div("edit-row__hint") { +EnglishStrings.USER_NAME_PARTS_HINT }
        }
    }
    div("edit-row") {
        span("edit-row__label") { +EnglishStrings.USER_FAMILY_NAME_LABEL }
        input(classes = "edit-row__field") {
            type = InputType.text
            name = "familyName"
            value = familyName
            placeholder = "Doe"
        }
    }
}

private fun FlowContent.readOnlyBadgesRow(
    label: String,
    items: List<String>,
    manageUrl: String,
) {
    div("edit-row") {
        span("edit-row__label") { +label }
        div {
            div("read-only-badges") {
                if (items.isEmpty()) {
                    span("edit-row__hint") { +"None assigned" }
                } else {
                    items.forEach { name ->
                        span("badge badge--id-muted") { +name }
                    }
                }
            }
            div("edit-row__hint") {
                +"Managed elsewhere. "
                a(href = manageUrl) { +"manage ${label.lowercase()}" }
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
    val givenName: String = "",
    val familyName: String = "",
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
    pageSize: Int = DEFAULT_USER_PAGE_SIZE,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Users · ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "users",
            loggedInAs = loggedInAs,
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
                            val start = (page - 1) * pageSize + 1
                            val end = start + users.size - 1
                            +"Showing $start\u2013$end of $totalCount result$suffix for \u201c$search\u201d"
                        } else {
                            +"$totalCount result$suffix for \u201c$search\u201d"
                        }
                    } else if (totalPages > 1) {
                        val start = (page - 1) * pageSize + 1
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
                        if (search != null) "?q=${search.encodeURLParameter()}&" else "?",
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
            pageTitle = "New User · ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "users",
            loggedInAs = loggedInAs,
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
                errorNotice(error)
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
                                autoComplete = "off"
                                attributes["spellcheck"] = "false"
                                attributes["pattern"] = "[a-zA-Z0-9._@+-]+"
                            }
                            div("edit-row__hint") {
                                +"Letters, digits, dots, underscores, hyphens, @, and +. Immutable after creation."
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
                            autoComplete = "off"
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
                    namePartRows(givenName = prefill.givenName, familyName = prefill.familyName)
                    // ── Credential setup radio ──────────────────
                    div("edit-row") {
                        span("edit-row__label") { +"Credential setup" }
                        div("radio-group") {
                            label("radio-row") {
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
                                div("radio-row__body") {
                                    span("radio-row__label") { +EnglishStrings.INVITE_RADIO_SEND }
                                    span("radio-row__desc") {
                                        if (workspace.isSmtpReady) {
                                            +EnglishStrings.INVITE_RADIO_SEND_HINT
                                        } else {
                                            +EnglishStrings.INVITE_RADIO_SMTP_HINT
                                        }
                                    }
                                }
                            }
                            label("radio-row") {
                                input(type = InputType.radio, name = "setupMode") {
                                    value = "password"
                                    id = "setupMode_password"
                                    if (!workspace.isSmtpReady) checked = true
                                    attributes["data-setup-toggle"] = "password"
                                }
                                div("radio-row__body") {
                                    span("radio-row__label") { +EnglishStrings.INVITE_RADIO_PASSWORD }
                                    span("radio-row__desc") {
                                        +EnglishStrings.PASSWORD_HINT_USER_CAN_CHANGE
                                    }
                                }
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

// ─── User Attributes section (rendered on user detail page) ─────────────────

private fun FlowContent.userAttributesSection(
    workspace: Tenant,
    user: User,
    attributes: Map<String, String>,
    mappedKeys: Map<String, String>,
    error: String?,
) {
    val slug = workspace.slug
    val userIdValue = user.id?.value ?: return
    val base = "/admin/workspaces/$slug/users/$userIdValue/attributes"

    div("ov-card") {
        div("ov-card__section-label") {
            span { +"Custom Attributes" }
            a(href = "$base/new", classes = "btn btn--ghost btn--sm") {
                +"Add attribute"
            }
        }

        if (error != null) {
            errorNotice(error)
        }

        if (attributes.isEmpty()) {
            emptyState(
                iconName = "code",
                title = "No custom attributes",
                description =
                    "Add key-value pairs to this user. Configure how they appear in JWTs under " +
                        "Settings → Claim Mappers.",
            )
        } else {
            table("data-table") {
                thead {
                    tr {
                        th { +"Key" }
                        th { +"Value" }
                        th { style = "width:120px;" }
                    }
                }
                tbody {
                    attributes.entries
                        .sortedBy { it.key }
                        .forEach { (key, value) ->
                            tr {
                                td {
                                    span("data-table__meta") { +key }
                                    val claimName = mappedKeys[key]
                                    if (claimName != null) {
                                        div {
                                            style = "margin-top:4px;"
                                            span("badge badge--id-muted") { +"→ $claimName" }
                                        }
                                    } else {
                                        div {
                                            style = "margin-top:4px;font-size:11px;color:var(--color-subtle);"
                                            a(
                                                href =
                                                    "/admin/workspaces/$slug/settings/claim-mappers/new" +
                                                        "?attributeKey=$key",
                                            ) { +"Configure mapping →" }
                                        }
                                    }
                                }
                                td { +value }
                                td {
                                    div("data-table__actions") {
                                        a(
                                            href = "$base/${encodeUriComponent(key)}/edit",
                                            classes = "btn btn--ghost btn--sm",
                                        ) { +"Edit" }
                                        postButton(
                                            action = "$base/${encodeUriComponent(key)}/delete",
                                            label = "Delete",
                                            btnClass = "btn btn--danger btn--sm",
                                            confirmMessage =
                                                if (mappedKeys.containsKey(key)) {
                                                    "Delete attribute '$key'? The '${mappedKeys[key]}' claim " +
                                                        "will stop appearing in tokens for this user."
                                                } else {
                                                    "Delete attribute '$key'?"
                                                },
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

internal fun userAttributeFormPageImpl(
    workspace: Tenant,
    user: User,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    existingKey: String? = null,
    prefillKey: String = "",
    prefillValue: String = "",
    error: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug
        val userIdValue = user.id?.value ?: 0
        val isEdit = existingKey != null

        adminShell(
            pageTitle = "${if (isEdit) "Edit Attribute" else "Add Attribute"} · ${user.username}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            loggedInAs = loggedInAs,
            activeAppSection = "users",
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    slug to "/admin/workspaces/$slug",
                    "Users" to "/admin/workspaces/$slug/users",
                    user.username to "/admin/workspaces/$slug/users/$userIdValue",
                    (if (isEdit) "Edit Attribute" else "Add Attribute") to null,
                )

                div("page-header") {
                    div("page-header__left") {
                        div("page-header__identity") {
                            h1("page-header__title") {
                                +(if (isEdit) "Edit Attribute" else "Add Attribute")
                            }
                            p("page-header__sub") {
                                +"Attribute values are stored as strings. They appear in JWTs only when a "
                                +"claim mapper is configured for the key."
                            }
                        }
                    }
                    div("page-header__actions") {
                        button(type = ButtonType.submit, classes = "btn btn--primary") {
                            attributes["form"] = "attribute-form"
                            +(if (isEdit) "Save" else "Add Attribute")
                        }
                    }
                }

                if (error != null) {
                    errorNotice(error)
                }

                div("ov-card") {
                    div("ov-card__section-label") { +"Attribute Details" }
                    form(
                        action =
                            if (isEdit) {
                                "/admin/workspaces/$slug/users/$userIdValue/attributes/" +
                                    encodeUriComponent(existingKey)
                            } else {
                                "/admin/workspaces/$slug/users/$userIdValue/attributes"
                            },
                        method = FormMethod.post,
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                    ) {
                        id = "attribute-form"

                        div("edit-row") {
                            span("edit-row__label") { +"Key" }
                            div {
                                input(type = InputType.text, name = "key") {
                                    classes = setOf("edit-row__field")
                                    required = true
                                    maxLength = "64"
                                    placeholder = "department"
                                    value = prefillKey
                                    if (isEdit) readonly = true
                                }
                                div("edit-row__hint") {
                                    +"Any string key. Common examples: department, tier, region, employee_id. "
                                    +"Keys are opaque to KotAuth."
                                }
                            }
                        }

                        div("edit-row") {
                            span("edit-row__label") { +"Value" }
                            div {
                                textArea {
                                    classes = setOf("edit-row__field")
                                    name = "value"
                                    rows = "3"
                                    attributes["maxlength"] = "1024"
                                    placeholder = "e.g. engineering, premium, us-east-1"
                                    +prefillValue
                                }
                                div("edit-row__hint") {
                                    +"Stored as plain text (max 1024 characters). Callers serialize their own types."
                                }
                            }
                        }
                    }
                }
            }
        }
    }

private fun encodeUriComponent(input: String): String =
    java.net.URLEncoder.encode(input, "UTF-8").replace("+", "%20")


/**
 * The providers this account can sign in through.
 *
 * The end user could already see this on their own portal; the administrator supporting them
 * could not, so "why can this person not sign in with SSO" had no answer on this page.
 */
private fun FlowContent.linkedIdentitiesCard(accounts: List<SocialAccount>) {
    div("ov-card") {
        div("ov-card__section-label") { +EnglishStrings.LINKED_IDENTITIES_HEADING }
        if (accounts.isEmpty()) {
            div("ov-card__row--stacked") {
                span("ov-card__value--muted") { +EnglishStrings.LINKED_IDENTITIES_EMPTY }
            }
        } else {
            table("data-table") {
                thead {
                    tr {
                        th { +EnglishStrings.LINKED_IDENTITIES_COL_PROVIDER }
                        th { +EnglishStrings.LINKED_IDENTITIES_COL_ACCOUNT }
                        th { +EnglishStrings.LINKED_IDENTITIES_COL_SUBJECT }
                        th { +EnglishStrings.LINKED_IDENTITIES_COL_LINKED }
                    }
                }
                tbody {
                    accounts.sortedBy { it.provider.value }.forEach { account ->
                        tr {
                            td { span("data-table__name") { +EnglishStrings.providerDisplayName(account.provider) } }
                            td {
                                span("data-table__email") {
                                    +(account.providerEmail?.takeIf { it.isNotBlank() } ?: "\u2014")
                                }
                            }
                            // The provider's own subject claim is the identity the link is keyed
                            // on; the email beside it can change without the link changing.
                            td { span("data-table__meta") { +account.providerUserId } }
                            td { span("data-table__meta") { +account.linkedAt.toDisplayString() } }
                        }
                    }
                }
            }
            div("ov-card__row--stacked") {
                span("ov-card__value--muted") { +EnglishStrings.LINKED_IDENTITIES_HINT }
            }
        }
    }
}


/**
 * The impersonation this admin session is holding, wherever they are in the console.
 *
 * Without it the only way to notice one was running was to start another and watch the first
 * disappear, which is the case the standing warning was written for and never wired to.
 */
private fun FlowContent.activeImpersonationCard(active: ActiveImpersonation) {
    div("ov-card") {
        div("ov-card__section-label") { +EnglishStrings.IMPERSONATION_ACTIVE_HEADING }
        div("ov-card__row") {
            span("ov-card__label") { +EnglishStrings.IMPERSONATION_ACTIVE_USER }
            span("ov-card__value") {
                a(
                    href = "/admin/workspaces/${active.targetWorkspaceSlug}/users/${active.targetUserId}",
                    classes = "data-table__id",
                ) { +active.targetUsername }
            }
        }
        div("ov-card__row") {
            span("ov-card__label") { +EnglishStrings.IMPERSONATION_ACTIVE_SINCE }
            span("ov-card__value ov-card__value--muted") { +active.startedAt.toDisplayString() }
        }
        div("ov-card__actions") {
            postButton(
                action = "/admin/impersonation/${active.sessionId}/stop",
                label = EnglishStrings.IMPERSONATION_STOP_BUTTON,
                btnClass = "btn btn--danger btn--sm",
                confirmMessage = EnglishStrings.IMPERSONATION_STOP_CONFIRM,
            )
        }
    }
}
