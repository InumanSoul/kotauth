package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.AuthMethodRow
import com.kauth.domain.model.Tenant
import kotlinx.html.*

internal fun HTML.signInMethodsAdminPage(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    rows: List<AuthMethodRow>,
    toastMessage: String? = null,
    error: String? = null,
) {
    val slug = workspace.slug

    adminShell(
        pageTitle = "${EnglishStrings.ADMIN_NAV_SIGN_IN_METHODS} — ${workspace.displayName}",
        activeRail = "security",
        activeAppSection = "sign-in-methods",
        allWorkspaces = allWorkspaces,
        workspaceName = workspace.displayName,
        workspaceSlug = slug,
        workspaceLogoUrl = workspace.theme.logoUrl,
        loggedInAs = loggedInAs,
        contentClass = "content-outer",
        toastMessage = toastMessage,
    ) {
        div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                EnglishStrings.ADMIN_NAV_SIGN_IN_METHODS to null,
            )

            pageHeader(
                title = EnglishStrings.ADMIN_NAV_SIGN_IN_METHODS,
                subtitle = "Configure which authentication methods this workspace supports.",
                actions = {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--primary")
                        attributes["form"] = "sign-in-methods-form"
                        +"Save changes"
                    }
                },
            )

            if (error != null) {
                div("notice notice--error") { +error }
            }

            form(
                action = "/admin/workspaces/$slug/settings/sign-in-methods",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                id = "sign-in-methods-form"
                div {
                    with(AuthMethodsGridView) { render(rows, workspace) }
                }
            }
        }
    }
}
