package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import kotlinx.html.*

internal fun HTML.passkeysAdminPage(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    userWithPasskey: List<Pair<User, Boolean>>,
) {
    val slug = workspace.slug
    val enrolledUsers = userWithPasskey.count { (_, hasPasskey) -> hasPasskey }
    val totalUsers = userWithPasskey.size
    val enrollmentRate = if (totalUsers > 0) "${Math.round(enrolledUsers * 100.0 / totalUsers)}%" else "—"
    val enrollUrl = "/t/$slug/account/passkeys"
    val signInMethodsUrl = "/admin/workspaces/$slug/settings/sign-in-methods"

    adminShell(
        pageTitle = "${EnglishStrings.ADMIN_PASSKEYS_PAGE_TITLE} — ${workspace.displayName}",
        activeRail = "security",
        activeAppSection = "passkeys",
        allWorkspaces = allWorkspaces,
        workspaceName = workspace.displayName,
        workspaceSlug = slug,
        workspaceLogoUrl = workspace.theme.logoUrl,
        loggedInAs = loggedInAs,
        contentClass = "content-outer",
    ) {
        div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Security" to "/admin/workspaces/$slug/settings/security",
                EnglishStrings.ADMIN_PASSKEYS_PAGE_TITLE to null,
            )

            pageHeader(
                title = EnglishStrings.ADMIN_PASSKEYS_PAGE_TITLE,
                subtitleContent = {
                    +"Passkey enrollment status for ${workspace.displayName}. Configuration: "
                    val badgeMod = if (workspace.passkeysEnabled) "badge--active" else "badge--inactive"
                    val badgeLabel = if (workspace.passkeysEnabled) "Enabled" else "Disabled"
                    span("badge $badgeMod") { +badgeLabel }
                },
            )

            when {
                !workspace.passkeysEnabled && enrolledUsers == 0 ->
                    notice(
                        title = EnglishStrings.ADMIN_PASSKEYS_ALERT_DISABLED_NO_USERS_TITLE,
                        description = EnglishStrings.ADMIN_PASSKEYS_ALERT_DISABLED_NO_USERS_DESC,
                        linkHref = signInMethodsUrl,
                        linkText = EnglishStrings.ADMIN_PASSKEYS_ALERT_SIGN_IN_METHODS_LINK,
                    )
                workspace.passkeysEnabled && enrolledUsers == 0 ->
                    notice(
                        title = EnglishStrings.ADMIN_PASSKEYS_ALERT_ENABLED_NO_USERS_TITLE,
                        description = EnglishStrings.ADMIN_PASSKEYS_ALERT_ENABLED_NO_USERS_DESC,
                        modifier = "notice--info",
                        iconName = "info",
                    )
                !workspace.passkeysEnabled && enrolledUsers > 0 ->
                    notice(
                        title = EnglishStrings.ADMIN_PASSKEYS_ALERT_DISABLED_HAS_USERS_TITLE,
                        description =
                            "$enrolledUsers users have credentials enrolled. " +
                                "Re-enable to let them use passkeys.",
                        linkHref = signInMethodsUrl,
                        linkText = EnglishStrings.ADMIN_PASSKEYS_ALERT_SIGN_IN_METHODS_LINK,
                    )
            }

            div("insight-bar insight-bar--cols-2") {
                div("insight-item insight-item--static") {
                    span("insight-item__label") { +"Enrolled Users" }
                    span("insight-item__value insight-item__value--mono") {
                        +"$enrolledUsers"
                        span("insight-item__denominator") { +" / $totalUsers" }
                    }
                    span("insight-item__hint") { +"$enrollmentRate enrollment rate" }
                }

                div("insight-item insight-item--static") {
                    span("insight-item__label") { +"Not Enrolled" }
                    val notEnrolled = totalUsers - enrolledUsers
                    val valueClass =
                        if (notEnrolled == 0) {
                            "insight-item__value insight-item__value--ok"
                        } else {
                            "insight-item__value"
                        }
                    span(valueClass) { +"$notEnrolled" }
                    span("insight-item__hint") {
                        +if (notEnrolled == 1) "user without a passkey" else "users without a passkey"
                    }
                }
            }

            if (workspace.passkeysEnabled) {
                ovCard {
                    ovSectionLabel(EnglishStrings.ADMIN_PASSKEYS_ENROLLMENT_URL_LABEL)
                    div("info-card-body") {
                        p("info-card-body__desc") { +EnglishStrings.ADMIN_PASSKEYS_ENROLLMENT_URL_DESC }
                        div("copy-field") {
                            span("copy-field__value") { +enrollUrl }
                            button(classes = "copy-field__btn") {
                                type = ButtonType.button
                                attributes["data-copy"] = enrollUrl
                                attributes["title"] = "Copy"
                                inlineSvgIcon("copy", "Copy")
                            }
                        }
                    }
                }
            }

            ovCard {
                ovSectionLabel("Users")
                val sortedUsers =
                    userWithPasskey
                        .sortedWith(compareBy({ !it.second }, { it.first.username }))
                if (sortedUsers.isEmpty()) {
                    emptyState(
                        iconName = "user",
                        title = EnglishStrings.ADMIN_PASSKEYS_EMPTY_USERS_TITLE,
                        description = EnglishStrings.ADMIN_PASSKEYS_EMPTY_USERS_DESC,
                    )
                } else {
                    table("data-table") {
                        thead {
                            tr {
                                th { +EnglishStrings.ADMIN_MFA_TABLE_COL_USERNAME }
                                th { +EnglishStrings.ADMIN_MFA_TABLE_COL_FULL_NAME }
                                th { +EnglishStrings.ADMIN_MFA_TABLE_COL_EMAIL }
                                th { +EnglishStrings.ADMIN_PASSKEYS_TABLE_COL_STATUS }
                            }
                        }
                        tbody {
                            sortedUsers.forEach { (u, hasPasskey) ->
                                tr {
                                    td {
                                        a(
                                            "/admin/workspaces/$slug/users/${u.id?.value}",
                                            classes = "data-table__id",
                                        ) { +u.username }
                                    }
                                    td { span("data-table__name") { +u.fullName } }
                                    td { span("data-table__email") { +u.email } }
                                    td {
                                        if (hasPasskey) {
                                            span("badge badge--active") {
                                                +EnglishStrings.ADMIN_PASSKEYS_TABLE_BADGE_ENROLLED
                                            }
                                        } else {
                                            span("badge badge--inactive") {
                                                +EnglishStrings.ADMIN_PASSKEYS_TABLE_BADGE_NOT_ENROLLED
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
