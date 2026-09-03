package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.Tenant
import kotlinx.html.*

internal fun resourceServersListPageImpl(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    resources: List<ResourceServer>,
    error: String? = null,
    toastMessage: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug
        adminShell(
            pageTitle = "${EnglishStrings.API_PAGE_TITLE} — ${workspace.displayName}",
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            loggedInAs = loggedInAs,
            activeAppSection = "apis",
            contentClass = "content-outer",
            toastMessage = toastMessage,
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    slug to "/admin/workspaces/$slug",
                    EnglishStrings.API_NAV_LABEL to null,
                )

                pageHeader(
                    title = EnglishStrings.API_PAGE_TITLE,
                    subtitle = EnglishStrings.API_PAGE_SUBTITLE,
                    actions = {
                        primaryLink(
                            "/admin/workspaces/$slug/settings/apis/new",
                            EnglishStrings.API_ADD,
                            "plus",
                        )
                    },
                )

                if (error != null) {
                    errorNotice(error)
                }

                if (resources.isEmpty()) {
                    div("ov-card") {
                        emptyState(
                            iconName = "key",
                            title = EnglishStrings.API_EMPTY_TITLE,
                            description = EnglishStrings.API_EMPTY_BODY,
                        )
                    }
                } else {
                    div("ov-card") {
                        table("data-table") {
                            thead {
                                tr {
                                    th { +EnglishStrings.API_LIST_COLUMN_NAME }
                                    th { +EnglishStrings.API_LIST_COLUMN_IDENTIFIER }
                                    th { style = "width:120px;"; +EnglishStrings.API_LIST_COLUMN_STATUS }
                                    th { style = "width:140px;" }
                                }
                            }
                            tbody {
                                resources.forEach { rs ->
                                    tr {
                                        td {
                                            a(href = "/admin/workspaces/$slug/settings/apis/${rs.id!!.value}/edit") {
                                                +rs.name
                                            }
                                        }
                                        td {
                            span("data-table__id") { +rs.identifier }
                            if (rs.scopes.isNotEmpty()) {
                                div("badge-row") {
                                    rs.scopes.take(5).forEach {
                                        span("badge badge--muted") { +it }
                                    }
                                    if (rs.scopes.size > 5) {
                                        span("badge badge--muted") { +"+${rs.scopes.size - 5}" }
                                    }
                                }
                            }
                        }
                                        td {
                                            if (rs.enabled) {
                                                span("badge badge--active") { +"Enabled" }
                                            } else {
                                                span("badge badge--inactive") { +"Disabled" }
                                            }
                                        }
                                        td {
                                            a(
                                                href =
                                                    "/admin/workspaces/$slug/settings/apis/" +
                                                        "${rs.id!!.value}/edit",
                                                classes = "btn btn--ghost btn--sm",
                                            ) { +"Edit" }
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

internal fun clientAuthorizedApisPageImpl(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    application: com.kauth.domain.model.Application,
    allApps: List<com.kauth.domain.model.Application>,
    allResources: List<ResourceServer>,
    authorizedIds: Set<Int>,
    error: String? = null,
    toastMessage: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug
        val appPairs = allApps.map { it.clientId to it.name }
        adminShell(
            pageTitle = "${EnglishStrings.API_AUTHORIZED_CLIENTS_HEADING} — ${application.name}",
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            apps = appPairs,
            activeAppSlug = application.clientId,
            loggedInAs = loggedInAs,
            activeAppSection = "applications",
            contentClass = "content-outer",
            toastMessage = toastMessage,
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    slug to "/admin/workspaces/$slug",
                    "Applications" to "/admin/workspaces/$slug/applications",
                    application.name to "/admin/workspaces/$slug/applications/${application.clientId}",
                    EnglishStrings.API_AUTHORIZED_CLIENTS_HEADING to null,
                )

                val formId = "authorized-apis-form"
                pageHeader(
                    title = EnglishStrings.API_AUTHORIZED_CLIENTS_HEADING,
                    actions = {
                        a(
                            href = "/admin/workspaces/$slug/applications/${application.clientId}",
                            classes = "btn btn--ghost",
                        ) { +"Cancel" }
                        if (allResources.isNotEmpty()) {
                            button(type = ButtonType.submit, classes = "btn btn--primary") {
                                attributes["form"] = formId
                                +"Save"
                            }
                        }
                    },
                )

                if (error != null) {
                    errorNotice(error)
                }

                if (allResources.isEmpty()) {
                    div("ov-card") {
                        emptyState(
                            iconName = "key",
                            title = EnglishStrings.API_AUTHORIZED_CLIENTS_EMPTY_TITLE,
                            description = EnglishStrings.API_AUTHORIZED_CLIENTS_EMPTY_BODY,
                        ) {
                            a(
                                href = "/admin/workspaces/$slug/settings/apis/new",
                                classes = "btn btn--primary",
                            ) { +EnglishStrings.API_AUTHORIZED_CLIENTS_EMPTY_CTA }
                        }
                    }
                } else {
                    val gridId = "authorized-apis-grid"
                    val totalSelectable = allResources.count { it.enabled }
                    form(
                        action =
                            "/admin/workspaces/$slug/applications/" +
                                "${application.clientId}/authorized-apis",
                        method = FormMethod.post,
                        classes = "edit-form",
                    ) {
                        id = formId
                        div("ov-card") {
                            div("chip-grid__header") {
                                span("chip-grid__header-label") { +EnglishStrings.API_AUTHORIZED_CLIENTS_HINT }
                                div("chip-grid__header-actions") {
                                    span("chip-grid__count") {
                                        id = "$gridId-count"
                                        +"${authorizedIds.size} / $totalSelectable selected"
                                    }
                                    button(type = ButtonType.button) {
                                        classes = setOf("chip-grid__toggle")
                                        attributes["data-chips-all"] = gridId
                                        +EnglishStrings.API_AUTHORIZED_CLIENTS_ALL
                                    }
                                    button(type = ButtonType.button) {
                                        classes = setOf("chip-grid__toggle")
                                        attributes["data-chips-none"] = gridId
                                        +EnglishStrings.API_AUTHORIZED_CLIENTS_NONE
                                    }
                                }
                            }
                            div("chip-grid") {
                                id = gridId
                                allResources.forEach { rs ->
                                    val rsId = rs.id?.value ?: return@forEach
                                    label("scope-chip${if (!rs.enabled) " scope-chip--disabled" else ""}") {
                                        input(type = InputType.checkBox, name = "resource") {
                                            value = rsId.toString()
                                            if (rsId in authorizedIds) {
                                                attributes["checked"] = "checked"
                                            }
                                            if (!rs.enabled) {
                                                attributes["disabled"] = "disabled"
                                            }
                                        }
                                        span("scope-chip__label") { +rs.name }
                                        span("badge badge--id") { +rs.identifier }
                                        if (!rs.enabled) {
                                            span("badge badge--inactive") { +"Disabled" }
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

internal fun resourceServerFormPageImpl(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    prefill: ResourceServer? = null,
    error: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug
        val existingId = prefill?.id
        val isEdit = existingId != null
        val title = if (isEdit) EnglishStrings.API_FORM_EDIT_TITLE else EnglishStrings.API_FORM_NEW_TITLE
        val submitUrl =
            if (existingId != null) {
                "/admin/workspaces/$slug/settings/apis/${existingId.value}"
            } else {
                "/admin/workspaces/$slug/settings/apis"
            }

        adminShell(
            pageTitle = "$title — ${workspace.displayName}",
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            loggedInAs = loggedInAs,
            activeAppSection = "apis",
            contentClass = "content-outer",
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    slug to "/admin/workspaces/$slug",
                    EnglishStrings.API_NAV_LABEL to "/admin/workspaces/$slug/settings/apis",
                    title to null,
                )

                val formId = "api-form"
                val submitLabel = if (isEdit) "Save changes" else "Register API"

                pageHeader(
                    title = title,
                    actions = {
                        a(
                            href = "/admin/workspaces/$slug/settings/apis",
                            classes = "btn btn--ghost",
                        ) { +"Cancel" }
                        button(type = ButtonType.submit, classes = "btn btn--primary") {
                            attributes["form"] = formId
                            +submitLabel
                        }
                    },
                )

                if (error != null) {
                    errorNotice(error)
                }

                form(action = submitUrl, method = FormMethod.post, classes = "edit-form") {
                    id = formId
                    div("ov-card") {
                        div("edit-row") {
                            span("edit-row__label") { +EnglishStrings.API_FIELD_IDENTIFIER }
                            div {
                                input(type = InputType.text, name = "identifier") {
                                    classes = setOf("edit-row__field", "edit-row__field--mono")
                                    value = prefill?.identifier.orEmpty()
                                    attributes["required"] = "required"
                                    attributes["maxlength"] = "255"
                                    if (isEdit) {
                                        attributes["disabled"] = "disabled"
                                    }
                                    placeholder = "https://api.example.com or payment-api"
                                }
                                div("edit-row__hint") {
                                    +if (isEdit) {
                                        EnglishStrings.API_FIELD_IDENTIFIER_HINT_LOCKED
                                    } else {
                                        EnglishStrings.API_FIELD_IDENTIFIER_HINT_NEW
                                    }
                                }
                            }
                        }
                        div("edit-row") {
                            span("edit-row__label") { +EnglishStrings.API_FIELD_NAME }
                            div {
                                input(type = InputType.text, name = "name") {
                                    classes = setOf("edit-row__field")
                                    value = prefill?.name.orEmpty()
                                    attributes["required"] = "required"
                                    attributes["maxlength"] = "100"
                                }
                                div("edit-row__hint") { +EnglishStrings.API_FIELD_NAME_HINT }
                            }
                        }
                        div("edit-row") {
                            span("edit-row__label") { +EnglishStrings.API_FIELD_DESCRIPTION }
                            div {
                                textArea {
                                    classes = setOf("edit-row__field")
                                    attributes["name"] = "description"
                                    attributes["rows"] = "3"
                                    +(prefill?.description ?: "")
                                }
                                div("edit-row__hint") { +EnglishStrings.API_FIELD_DESCRIPTION_HINT }
                            }
                        }
                        div("edit-row") {
                            span("edit-row__label") { +EnglishStrings.RESOURCE_SERVER_SCOPES_LABEL }
                            div {
                                textArea {
                                    classes = setOf("edit-row__field")
                                    id = "scopes"
                                    attributes["name"] = "scopes"
                                    attributes["rows"] = "6"
                                    placeholder = "read:invoices\nwrite:invoices"
                                    +(prefill?.scopes?.joinToString("\n").orEmpty())
                                }
                                div("edit-row__hint") { +EnglishStrings.RESOURCE_SERVER_SCOPES_HINT }
                            }
                        }
                    }
                }

                if (prefill != null && existingId != null) {
                    div("ov-card") {
                        div("ov-card__section-label ov-card__section-label--danger") { +"Danger zone" }
                        div("danger-zone") {
                            dangerZoneCard(
                                title = if (prefill.enabled) "Disable API" else "Enable API",
                                description =
                                    if (prefill.enabled) {
                                        "Clients targeting this API will receive an invalid_target error on " +
                                            "the next token request. Existing tokens remain valid until they expire."
                                    } else {
                                        "Re-enable token issuance for this API."
                                    },
                                warning = false,
                            ) {
                                postButton(
                                    action =
                                        "/admin/workspaces/$slug/settings/apis/" +
                                            "${existingId.value}/${if (prefill.enabled) "disable" else "enable"}",
                                    label = if (prefill.enabled) "Disable" else "Enable",
                                    btnClass = "btn btn--danger btn--sm",
                                )
                            }

                            dangerZoneCard(
                                title = "Delete API",
                                description =
                                    "Removes this API and every client authorization that referenced it. " +
                                        "Tokens already issued remain valid until they expire. " +
                                        "Type the audience identifier to confirm.",
                                warning = true,
                            ) {
                                form(
                                    action = "/admin/workspaces/$slug/settings/apis/${existingId.value}/delete",
                                    method = FormMethod.post,
                                    classes = "inline-form",
                                ) {
                                    input(type = InputType.text, name = "confirmIdentifier") {
                                        classes = setOf("edit-row__field", "edit-row__field--mono")
                                        attributes["pattern"] = escapeForHtmlPattern(prefill.identifier)
                                        attributes["required"] = "required"
                                        attributes["autocomplete"] = "off"
                                        attributes["data-confirm-slug"] = prefill.identifier
                                        attributes["data-confirm-target"] = "#api-delete-button"
                                        placeholder = prefill.identifier
                                    }
                                    button(type = ButtonType.submit, classes = "btn btn--danger btn--sm") {
                                        id = "api-delete-button"
                                        attributes["disabled"] = "disabled"
                                        +"Delete API"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
