package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantClaimMapper
import kotlinx.html.*

// ─── Claim Mappers — list page ──────────────────────────────────────────────

internal fun claimMappersListPageImpl(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    mappers: List<TenantClaimMapper>,
    error: String? = null,
    toastMessage: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "Claim Mappers — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            loggedInAs = loggedInAs,
            activeAppSection = "claim-mappers",
            contentClass = "content-outer",
            toastMessage = toastMessage,
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    slug to "/admin/workspaces/$slug",
                    "Settings" to "/admin/workspaces/$slug/settings",
                    "Claim Mappers" to null,
                )

                pageHeader(
                    title = "Claim Mappers",
                    subtitle =
                        "Control which user attributes appear as claims in access and ID tokens. " +
                            "Changes take effect on the next token issuance.",
                    actions = {
                        primaryLink(
                            "/admin/workspaces/$slug/settings/claim-mappers/new",
                            "Add Mapper",
                            "plus",
                        )
                    },
                )

                if (error != null) {
                    div("notice notice--error") { +error }
                }

                if (mappers.isEmpty()) {
                    div("ov-card") {
                        emptyState(
                            iconName = "key",
                            title = "No claim mappers configured",
                            description =
                                "Attributes won't appear in JWTs until you map them here. " +
                                    "Example: map attribute key 'plan' to claim name 'custom:plan' " +
                                    "to surface a billing tier in your access and ID tokens.",
                        )
                    }
                } else {
                    div("ov-card") {
                        div("ov-card__section-label") { +"Configured Mappers" }
                        table("data-table") {
                            thead {
                                tr {
                                    th { +"Attribute Key" }
                                    th { +"Claim Name" }
                                    th { style = "width:120px;"; +"Access Token" }
                                    th { style = "width:120px;"; +"ID Token" }
                                    th { style = "width:80px;" }
                                }
                            }
                            tbody {
                                mappers.forEach { mapper ->
                                    tr {
                                        td {
                                            span("data-table__id") { +mapper.attributeKey }
                                        }
                                        td { +mapper.claimName }
                                        td { yesNoBadge(mapper.includeInAccess) }
                                        td { yesNoBadge(mapper.includeInId) }
                                        td {
                                            div {
                                                postButton(
                                                    action =
                                                        "/admin/workspaces/$slug/settings/claim-mappers/" +
                                                            "${mapper.attributeKey}/delete",
                                                    label = "Delete",
                                                    btnClass = "btn btn--danger btn--sm",
                                                    confirmMessage =
                                                        "Delete mapper for '${mapper.attributeKey}'? The " +
                                                            "'${mapper.claimName}' claim will stop appearing in tokens.",
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
    }

// ─── Claim Mappers — create/edit page ───────────────────────────────────────

internal fun claimMapperFormPageImpl(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    prefill: TenantClaimMapper? = null,
    error: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug
        val isEdit = prefill != null

        adminShell(
            pageTitle = "${if (isEdit) "Edit Mapper" else "New Mapper"} — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            loggedInAs = loggedInAs,
            activeAppSection = "claim-mappers",
            contentClass = "content-outer",
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    slug to "/admin/workspaces/$slug",
                    "Settings" to "/admin/workspaces/$slug/settings",
                    "Claim Mappers" to "/admin/workspaces/$slug/settings/claim-mappers",
                    (if (isEdit) "Edit Mapper" else "New Mapper") to null,
                )

                div("page-header") {
                    div("page-header__left") {
                        div("page-header__identity") {
                            h1("page-header__title") {
                                +(if (isEdit) "Edit Claim Mapper" else "Add Claim Mapper")
                            }
                            p("page-header__sub") {
                                +"Project a user attribute into JWT claims under a custom name."
                            }
                        }
                    }
                    div("page-header__actions") {
                        button(type = ButtonType.submit, classes = "btn btn--primary") {
                            attributes["form"] = "claim-mapper-form"
                            +(if (isEdit) "Save Changes" else "Create Mapper")
                        }
                    }
                }

                if (error != null) {
                    div("notice notice--error") { +error }
                }

                div("ov-card") {
                    div("ov-card__section-label") { +"Mapper Details" }
                    form(
                        action =
                            if (isEdit) {
                                "/admin/workspaces/$slug/settings/claim-mappers/${prefill.attributeKey}"
                            } else {
                                "/admin/workspaces/$slug/settings/claim-mappers"
                            },
                        method = FormMethod.post,
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                    ) {
                        id = "claim-mapper-form"

                        div("edit-row") {
                            span("edit-row__label") { +"Attribute Key" }
                            div {
                                input(type = InputType.text, name = "attributeKey") {
                                    classes = setOf("edit-row__field")
                                    required = true
                                    maxLength = "64"
                                    placeholder = "plan"
                                    value = prefill?.attributeKey ?: ""
                                    if (isEdit) readonly = true
                                }
                                div("edit-row__hint") {
                                    +"Must match exactly the key set on users via the attributes API."
                                }
                            }
                        }

                        div("edit-row") {
                            span("edit-row__label") { +"Claim Name" }
                            div {
                                input(type = InputType.text, name = "claimName") {
                                    classes = setOf("edit-row__field")
                                    required = true
                                    maxLength = "128"
                                    placeholder = "custom:plan"
                                    value = prefill?.claimName ?: ""
                                }
                                div("edit-row__hint") {
                                    +"Avoid reserved OIDC names (sub, iss, aud, exp, iat, email, etc.). "
                                    +"Prefix with 'custom:' to prevent collisions."
                                }
                            }
                        }

                        div("edit-row") {
                            span("edit-row__label") { +"Include in" }
                            div {
                                label("radio-row") {
                                    input(type = InputType.checkBox, name = "includeInAccess") {
                                        value = "true"
                                        if (prefill?.includeInAccess != false) checked = true
                                    }
                                    span("radio-row__body") {
                                        span("radio-row__label") { +"Access Token" }
                                        span("radio-row__desc") {
                                            +"Claim appears in the short-lived bearer token sent with API requests."
                                        }
                                    }
                                }
                                label("radio-row") {
                                    input(type = InputType.checkBox, name = "includeInId") {
                                        value = "true"
                                        if (prefill?.includeInId == true) checked = true
                                    }
                                    span("radio-row__body") {
                                        span("radio-row__label") { +"ID Token" }
                                        span("radio-row__desc") {
                                            +"Claim appears in the OIDC id_token (consumed by clients to display user info)."
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

private fun TD.yesNoBadge(value: Boolean) {
    if (value) {
        span("badge badge--active") {
            span("badge__dot") {}
            +"Yes"
        }
    } else {
        span("badge badge--inactive") { +"No" }
    }
}
