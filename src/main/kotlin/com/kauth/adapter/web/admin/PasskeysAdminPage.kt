package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.Tenant
import kotlinx.html.*

internal fun HTML.passkeysAdminPage(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    enrolledCount: Int,
    totalUsers: Int,
) {
    val slug = workspace.slug

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
                subtitle = "Passkey enrollment and configuration for ${workspace.displayName}.",
            )

            div("insight-bar insight-bar--cols-1") {
                div("insight-item insight-item--static") {
                    span("insight-item__label") { +EnglishStrings.ADMIN_PASSKEYS_ENROLLMENT_LABEL }
                    span("insight-item__value insight-item__value--mono") {
                        +"$enrolledCount"
                        span("insight-item__denominator") { +" / $totalUsers" }
                    }
                    span("insight-item__hint") { +EnglishStrings.ADMIN_PASSKEYS_ENROLLMENT_HINT }
                }
            }

            div("ov-card") {
                div("ov-card__section-label") { +EnglishStrings.ADMIN_PASSKEYS_CONFIG_LABEL }
                p { +EnglishStrings.ADMIN_PASSKEYS_CONFIG_BODY }
                a(
                    href = "/admin/workspaces/$slug/settings/sign-in-methods",
                    classes = "btn btn--outline",
                ) {
                    +EnglishStrings.ADMIN_PASSKEYS_OPEN_POLICY
                }
            }
        }
    }
}
