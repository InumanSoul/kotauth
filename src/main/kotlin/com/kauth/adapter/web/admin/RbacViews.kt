package com.kauth.adapter.web.admin

import com.kauth.domain.model.Application
import com.kauth.domain.model.Group
import com.kauth.domain.model.Role
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import kotlinx.html.*

internal fun rolesListPageImpl(
    workspace: Tenant,
    roles: List<Role>,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "Roles — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "roles",
            loggedInAs = loggedInAs,
                  contentClass = "content-outer",
) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Roles" to null,
            )

            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Roles" }
                        p("page-header__sub") {
                            +"${roles.size} role${if (roles.size != 1) "s" else ""} in this workspace"
                        }
                    }
                }
                div("page-header__actions") {
                    primaryLink("/admin/workspaces/$slug/roles/create", "Create Role", "plus")
                }
            }

            if (roles.isEmpty()) {
                emptyState(
                    iconName = "lock",
                    title = "No roles yet",
                    description = "Create a role to define permissions for this workspace.",
                )
            } else {
                table("data-table") {
                    thead {
                        tr {
                            th { +"Name" }
                            th { +"Scope" }
                            th { +"Description" }
                            th { +"Composite" }
                            th { style = "width:70px;" }
                        }
                    }
                    tbody {
                        roles.forEach { role ->
                            tr {
                                td {
                                    a(
                                        href = "/admin/workspaces/$slug/roles/${role.id?.value}",
                                        classes = "data-table__id",
                                    ) { +role.name }
                                }
                                td {
                                    val isWorkspace = role.scope.value == "tenant"
                                    span(if (isWorkspace) "badge badge--active" else "badge badge--confidential") {
                                        +(if (isWorkspace) "workspace" else "application")
                                    }
                                }
                                td { span("data-table__name") { +(role.description ?: "\u2014") } }
                                td {
                                    span("data-table__name") {
                                        +(if (role.childRoleIds.isNotEmpty()) "${role.childRoleIds.size} children" else "\u2014")
                                    }
                                }
                                td {
                                    div("data-table__actions") {
                                        a(
                                            href = "/admin/workspaces/$slug/roles/${role.id?.value}",
                                            classes = "btn btn--ghost btn--sm",
                                        ) { +"Open" }
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

internal fun createRolePageImpl(
    workspace: Tenant,
    apps: List<Application>,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    error: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "New Role — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "roles",
            loggedInAs = loggedInAs,
                    contentClass = "content-outer",
) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Roles" to "/admin/workspaces/$slug/roles",
                "New Role" to null,
            )

            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Create Role" }
                        p("page-header__sub") { +"Add a role to ${workspace.displayName}." }
                    }
                }
                div("page-header__actions") {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--primary")
                        attributes["form"] = "create-role-form"
                        +"Create Role"
                    }
                }
            }

            if (error != null) {
                div("notice notice--error") { +error }
            }

            form(
                action = "/admin/workspaces/$slug/roles",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                id = "create-role-form"

                div("ov-card") {
                    div("ov-card__section-label") { +"Role Details" }
                    div("edit-row") {
                        span("edit-row__label") { +"Name" }
                        div {
                            input(type = InputType.text, name = "name") {
                                classes = setOf("edit-row__field")
                                required = true
                                placeholder = "e.g. admin, editor, viewer"
                                attributes["pattern"] = "[a-zA-Z0-9._-]+"
                            }
                            div("edit-row__hint") { +"Letters, digits, dots, underscores, hyphens only." }
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Description" }
                        input(type = InputType.text, name = "description") {
                            classes = setOf("edit-row__field")
                            placeholder = "Short description of what this role grants"
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Scope" }
                        div {
                            select {
                                classes = setOf("edit-row__field", "edit-row__field--select")
                                name = "scope"
                                id = "roleScope"
                                attributes["data-scope-toggle"] = "appField"
                                option {
                                    value = "tenant"
                                    +"Workspace (realm-level)"
                                }
                                option {
                                    value = "client"
                                    +"Application (app-scoped)"
                                }
                            }
                            div("edit-row__hint") {
                                +"Workspace roles apply across the entire workspace. Application roles are scoped to a specific app."
                            }
                        }
                    }
                    div("edit-row") {
                        id = "appField"
                        style = "display:none;"
                        span("edit-row__label") { +"Application" }
                        if (apps.isEmpty()) {
                            div("edit-row__hint") { +"No applications in this workspace yet. Create one first." }
                        } else {
                            select {
                                classes = setOf("edit-row__field", "edit-row__field--select")
                                name = "clientId"
                                option {
                                    value = ""
                                    +"\u2014 select application \u2014"
                                }
                                apps.forEach { app ->
                                    option {
                                        value = app.id.value.toString()
                                        +app.name
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

internal fun roleDetailPageImpl(
    workspace: Tenant,
    role: Role,
    allRoles: List<Role>,
    assignedUsers: List<User> = emptyList(),
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    toastMessage: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "${role.name} — Roles",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "roles",
            loggedInAs = loggedInAs,
            contentClass = "content-outer",
            toastMessage = toastMessage,
        ) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Roles" to "/admin/workspaces/$slug/roles",
                role.name to null,
            )

            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +role.name }
                        p("page-header__sub") { +"${role.scope.value} role \u00b7 ${role.description ?: "no description"}" }
                    }
                }
                div("page-header__actions") {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--primary")
                        attributes["form"] = "edit-role-form"
                        +"Save"
                    }
                    form(
                        action = "/admin/workspaces/$slug/roles/${role.id?.value}/delete",
                        method = FormMethod.post,
                    ) {
                        button(type = ButtonType.submit) {
                            classes = setOf("btn", "btn--danger")
                            attributes["data-confirm"] = "Delete role ${role.name}?"
                            +"Delete"
                        }
                    }
                }
            }

            // ── Edit name/description ────────────────────────────────
            form(
                action = "/admin/workspaces/$slug/roles/${role.id?.value}/edit",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                id = "edit-role-form"

                div("ov-card") {
                    div("ov-card__section-label") { +"Edit Role" }
                    div("edit-row") {
                        span("edit-row__label") { +"Name" }
                        input(type = InputType.text, name = "name") {
                            classes = setOf("edit-row__field")
                            required = true
                            value = role.name
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Description" }
                        input(type = InputType.text, name = "description") {
                            classes = setOf("edit-row__field")
                            value = role.description ?: ""
                        }
                    }
                }
            }

            // ── Composite children ───────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Composite Children" }
                if (role.childRoleIds.isEmpty()) {
                    p("edit-row__hint") {
                        style = "padding:12px 16px;"
                        +"No child roles."
                    }
                } else {
                    table("data-table") {
                        thead {
                            tr {
                                th { +"Child Role" }
                                th { style = "width:80px;" }
                            }
                        }
                        tbody {
                            role.childRoleIds.forEach { childId ->
                                val child = allRoles.find { it.id == childId }
                                tr {
                                    td { span("data-table__name") { +(child?.name ?: "#${childId.value}") } }
                                    td {
                                        form(
                                            action = "/admin/workspaces/$slug/roles/${role.id?.value}/remove-child",
                                            method = FormMethod.post,
                                        ) {
                                            input(type = InputType.hidden, name = "childRoleId") {
                                                value = childId.value.toString()
                                            }
                                            button(type = ButtonType.submit) {
                                                classes = setOf("btn", "btn--ghost", "btn--sm")
                                                +"Remove"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val availableChildren = allRoles.filter { it.id != role.id && it.id !in role.childRoleIds }
                if (availableChildren.isNotEmpty()) {
                    div("edit-actions") {
                        form(
                            action = "/admin/workspaces/$slug/roles/${role.id?.value}/children",
                            method = FormMethod.post,
                        ) {
                            style = "display:flex; align-items:center; gap:8px;"
                            select {
                                classes = setOf("edit-row__field", "edit-row__field--select")
                                name = "childRoleId"
                                availableChildren.forEach { r ->
                                    option {
                                        value = r.id?.value.toString()
                                        +r.name
                                    }
                                }
                            }
                            button(type = ButtonType.submit) {
                                classes = setOf("btn", "btn--primary")
                                +"Add Child"
                            }
                        }
                    }
                }
            }

            // ── Assigned users ───────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Assigned Users" }
                if (assignedUsers.isNotEmpty()) {
                    table("data-table") {
                        thead {
                            tr {
                                th { +"Username" }
                                th { +"Email" }
                                th { style = "width:80px;" }
                            }
                        }
                        tbody {
                            assignedUsers.forEach { u ->
                                tr {
                                    td {
                                        a(
                                            href = "/admin/workspaces/$slug/users/${u.id?.value}",
                                            classes = "data-table__name",
                                        ) { +u.username }
                                    }
                                    td { +u.email }
                                    td {
                                        form(
                                            action = "/admin/workspaces/$slug/roles/${role.id?.value}/unassign-user",
                                            method = FormMethod.post,
                                        ) {
                                            input(type = InputType.hidden, name = "userId") {
                                                value = u.id?.value.toString()
                                            }
                                            button(type = ButtonType.submit) {
                                                classes = setOf("btn", "btn--ghost")
                                                +"Remove"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val excludeParam =
                    assignedUsers.mapNotNull { it.id?.value }.joinToString(",")
                entityPicker(
                    pickerId = "role-user-picker",
                    searchUrl = "/admin/workspaces/$slug/roles/${role.id?.value}/search-users" +
                        if (excludeParam.isNotEmpty()) "?exclude=$excludeParam" else "",
                    placeholder = "Search users to assign\u2026",
                )
            }
                    }
}
    }

internal fun groupsListPageImpl(
    workspace: Tenant,
    groups: List<Group>,
    roles: List<Role>,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "Groups — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "groups",
            loggedInAs = loggedInAs,
                  contentClass = "content-outer",
) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Groups" to null,
            )

            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Groups" }
                        p("page-header__sub") {
                            +"${groups.size} group${if (groups.size != 1) "s" else ""} in this workspace"
                        }
                    }
                }
                div("page-header__actions") {
                    primaryLink("/admin/workspaces/$slug/groups/create", "Create Group", "plus")
                }
            }

            if (groups.isEmpty()) {
                emptyState(
                    iconName = "user",
                    title = "No groups yet",
                    description = "Create a group to organize users and assign roles in bulk.",
                )
            } else {
                table("data-table") {
                    thead {
                        tr {
                            th { +"Name" }
                            th { +"Parent" }
                            th { +"Roles" }
                            th { +"Description" }
                            th { style = "width:70px;" }
                        }
                    }
                    tbody {
                        groups.forEach { group ->
                            val parent = groups.find { it.id == group.parentGroupId }
                            val roleNames =
                                group.roleIds.mapNotNull { rid ->
                                    roles.find { it.id == rid }?.name
                                }
                            tr {
                                td {
                                    a(
                                        href = "/admin/workspaces/$slug/groups/${group.id?.value}",
                                        classes = "data-table__id",
                                    ) { +group.name }
                                }
                                td { span("data-table__name") { +(parent?.name ?: "\u2014") } }
                                td {
                                    span("data-table__name") {
                                        +(if (roleNames.isNotEmpty()) roleNames.joinToString(", ") else "\u2014")
                                    }
                                }
                                td { span("data-table__name") { +(group.description ?: "\u2014") } }
                                td {
                                    div("data-table__actions") {
                                        a(
                                            href = "/admin/workspaces/$slug/groups/${group.id?.value}",
                                            classes = "btn btn--ghost btn--sm",
                                        ) { +"Open" }
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

internal fun createGroupPageImpl(
    workspace: Tenant,
    groups: List<Group>,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    error: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "New Group — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "groups",
            loggedInAs = loggedInAs,
                    contentClass = "content-outer",
) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Groups" to "/admin/workspaces/$slug/groups",
                "New Group" to null,
            )

            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Create Group" }
                        p("page-header__sub") { +"Add a group to ${workspace.displayName}." }
                    }
                }
                div("page-header__actions") {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--primary")
                        attributes["form"] = "create-group-form"
                        +"Create Group"
                    }
                }
            }

            if (error != null) {
                div("notice notice--error") { +error }
            }

            form(
                action = "/admin/workspaces/$slug/groups",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                id = "create-group-form"

                div("ov-card") {
                    div("ov-card__section-label") { +"Group Details" }
                    div("edit-row") {
                        span("edit-row__label") { +"Name" }
                        input(type = InputType.text, name = "name") {
                            classes = setOf("edit-row__field")
                            required = true
                            placeholder = "e.g. engineering, marketing, ops"
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Description" }
                        input(type = InputType.text, name = "description") {
                            classes = setOf("edit-row__field")
                            placeholder = "What this group represents"
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Parent Group" }
                        div {
                            select {
                                classes = setOf("edit-row__field", "edit-row__field--select")
                                name = "parentGroupId"
                                option {
                                    value = ""
                                    +"\u2014 None (top-level) \u2014"
                                }
                                groups.forEach { g ->
                                    option {
                                        value = g.id?.value.toString()
                                        +g.name
                                    }
                                }
                            }
                            div("edit-row__hint") { +"Nested groups inherit the roles assigned to their parent." }
                        }
                    }
                }
            }
                    }
}
    }

internal fun groupDetailPageImpl(
    workspace: Tenant,
    group: Group,
    allGroups: List<Group>,
    allRoles: List<Role>,
    members: List<User>,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    toastMessage: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "${group.name} — Groups",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            activeAppSection = "groups",
            loggedInAs = loggedInAs,
            contentClass = "content-outer",
            toastMessage = toastMessage,
        ) {
            div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Groups" to "/admin/workspaces/$slug/groups",
                group.name to null,
            )

            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +group.name }
                        p("page-header__sub") {
                            val parent = allGroups.find { it.id == group.parentGroupId }
                            +(if (parent != null) "Child of ${parent.name}" else "Top-level group")
                            +" \u00b7 ${group.description ?: "no description"}"
                        }
                    }
                }
                div("page-header__actions") {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--primary")
                        attributes["form"] = "edit-group-form"
                        +"Save"
                    }
                    form(
                        action = "/admin/workspaces/$slug/groups/${group.id?.value}/delete",
                        method = FormMethod.post,
                    ) {
                        button(type = ButtonType.submit) {
                            classes = setOf("btn", "btn--danger")
                            attributes["data-confirm"] = "Delete group ${group.name}?"
                            +"Delete"
                        }
                    }
                }
            }

            // ── Edit name/description ────────────────────────────────
            form(
                action = "/admin/workspaces/$slug/groups/${group.id?.value}/edit",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                id = "edit-group-form"

                div("ov-card") {
                    div("ov-card__section-label") { +"Edit Group" }
                    div("edit-row") {
                        span("edit-row__label") { +"Name" }
                        input(type = InputType.text, name = "name") {
                            classes = setOf("edit-row__field")
                            required = true
                            value = group.name
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Description" }
                        input(type = InputType.text, name = "description") {
                            classes = setOf("edit-row__field")
                            value = group.description ?: ""
                        }
                    }
                }
            }

            // ── Assigned roles ───────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Assigned Roles" }
                if (group.roleIds.isEmpty()) {
                    p("edit-row__hint") {
                        style = "padding:12px 16px;"
                        +"No roles assigned."
                    }
                } else {
                    table("data-table") {
                        thead {
                            tr {
                                th { +"Role" }
                                th { +"Scope" }
                                th { style = "width:80px;" }
                            }
                        }
                        tbody {
                            group.roleIds.forEach { rid ->
                                val r = allRoles.find { it.id == rid }
                                tr {
                                    td { span("data-table__name") { +(r?.name ?: "#${rid.value}") } }
                                    td {
                                        span("badge badge--active") { +(r?.scope?.value ?: "?") }
                                    }
                                    td {
                                        form(
                                            action = "/admin/workspaces/$slug/groups/${group.id?.value}/unassign-role",
                                            method = FormMethod.post,
                                        ) {
                                            input(type = InputType.hidden, name = "roleId") {
                                                value = rid.value.toString()
                                            }
                                            button(type = ButtonType.submit) {
                                                classes = setOf("btn", "btn--ghost", "btn--sm")
                                                +"Remove"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val availableRoles = allRoles.filter { it.id !in group.roleIds }
                if (availableRoles.isNotEmpty()) {
                    div("edit-actions") {
                        form(
                            action = "/admin/workspaces/$slug/groups/${group.id?.value}/assign-role",
                            method = FormMethod.post,
                        ) {
                            style = "display:flex; align-items:center; gap:8px;"
                            select {
                                classes = setOf("edit-row__field", "edit-row__field--select")
                                name = "roleId"
                                availableRoles.forEach { r ->
                                    option {
                                        value = r.id?.value.toString()
                                        +r.name
                                    }
                                }
                            }
                            button(type = ButtonType.submit) {
                                classes = setOf("btn", "btn--primary", "btn--sm")
                                +"Assign Role"
                            }
                        }
                    }
                }
            }

            // ── Members ──────────────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Members (${members.size})" }
                if (members.isEmpty()) {
                    p("edit-row__hint") {
                        style = "padding:12px 16px;"
                        +"No members."
                    }
                } else {
                    table("data-table") {
                        thead {
                            tr {
                                th { +"Username" }
                                th { +"Email" }
                                th { style = "width:80px;" }
                            }
                        }
                        tbody {
                            members.forEach { u ->
                                tr {
                                    td { span("data-table__name") { +u.username } }
                                    td { span("data-table__meta") { +u.email } }
                                    td {
                                        form(
                                            action = "/admin/workspaces/$slug/groups/${group.id?.value}/remove-member",
                                            method = FormMethod.post,
                                        ) {
                                            input(type = InputType.hidden, name = "userId") {
                                                value = u.id?.value.toString()
                                            }
                                            button(type = ButtonType.submit) {
                                                classes = setOf("btn", "btn--ghost", "btn--sm")
                                                +"Remove"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val memberExclude =
                    members.mapNotNull { it.id?.value }.joinToString(",")
                entityPicker(
                    pickerId = "group-member-picker",
                    searchUrl = "/admin/workspaces/$slug/groups/${group.id?.value}/search-users" +
                        if (memberExclude.isNotEmpty()) "?exclude=$memberExclude" else "",
                    placeholder = "Search users to add\u2026",
                )
            }
                    }
}
    }
