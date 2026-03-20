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
    allWorkspaces: List<Pair<String, String>>,
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
            activeAppSection = "roles",
            loggedInAs = loggedInAs,
        ) {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Roles" to null,
            )

            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Roles" }
                        span("page-header__sub") {
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
                                        href = "/admin/workspaces/$slug/roles/${role.id}",
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
                                            href = "/admin/workspaces/$slug/roles/${role.id}",
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

internal fun createRolePageImpl(
    workspace: Tenant,
    apps: List<Application>,
    allWorkspaces: List<Pair<String, String>>,
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
            activeAppSection = "roles",
            loggedInAs = loggedInAs,
        ) {
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
                                        value = app.id.toString()
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

internal fun roleDetailPageImpl(
    workspace: Tenant,
    role: Role,
    allRoles: List<Role>,
    allUsers: List<User>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "${role.name} — Roles",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            activeAppSection = "roles",
            loggedInAs = loggedInAs,
        ) {
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
                        action = "/admin/workspaces/$slug/roles/${role.id}/delete",
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
                action = "/admin/workspaces/$slug/roles/${role.id}/edit",
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
                    table("key-table") {
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
                                    td { span("key-table__name") { +(child?.name ?: "#$childId") } }
                                    td {
                                        form(
                                            action = "/admin/workspaces/$slug/roles/${role.id}/remove-child",
                                            method = FormMethod.post,
                                        ) {
                                            input(type = InputType.hidden, name = "childRoleId") {
                                                value = childId.toString()
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
                            action = "/admin/workspaces/$slug/roles/${role.id}/children",
                            method = FormMethod.post,
                        ) {
                            style = "display:flex; align-items:center; gap:8px;"
                            select {
                                classes = setOf("edit-row__field", "edit-row__field--select")
                                name = "childRoleId"
                                availableChildren.forEach { r ->
                                    option {
                                        value = r.id.toString()
                                        +r.name
                                    }
                                }
                            }
                            button(type = ButtonType.submit) {
                                classes = setOf("btn", "btn--primary", "btn--sm")
                                +"Add Child"
                            }
                        }
                    }
                }
            }

            // ── Assigned users ───────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Assigned Users" }
                div("edit-actions") {
                    form(
                        action = "/admin/workspaces/$slug/roles/${role.id}/assign-user",
                        method = FormMethod.post,
                    ) {
                        style = "display:flex; align-items:center; gap:8px;"
                        select {
                            classes = setOf("edit-row__field", "edit-row__field--select")
                            name = "userId"
                            allUsers.forEach { u ->
                                option {
                                    value = u.id.toString()
                                    +"${u.username} (${u.email})"
                                }
                            }
                        }
                        button(type = ButtonType.submit) {
                            classes = setOf("btn", "btn--primary", "btn--sm")
                            +"Assign"
                        }
                    }
                }
            }
        }
    }

internal fun groupsListPageImpl(
    workspace: Tenant,
    groups: List<Group>,
    roles: List<Role>,
    allWorkspaces: List<Pair<String, String>>,
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
            activeAppSection = "groups",
            loggedInAs = loggedInAs,
        ) {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Groups" to null,
            )

            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Groups" }
                        span("page-header__sub") {
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
                                        href = "/admin/workspaces/$slug/groups/${group.id}",
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
                                            href = "/admin/workspaces/$slug/groups/${group.id}",
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

internal fun createGroupPageImpl(
    workspace: Tenant,
    groups: List<Group>,
    allWorkspaces: List<Pair<String, String>>,
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
            activeAppSection = "groups",
            loggedInAs = loggedInAs,
        ) {
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
                                        value = g.id.toString()
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

internal fun groupDetailPageImpl(
    workspace: Tenant,
    group: Group,
    allGroups: List<Group>,
    allRoles: List<Role>,
    members: List<User>,
    allUsers: List<User>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "${group.name} — Groups",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            activeAppSection = "groups",
            loggedInAs = loggedInAs,
        ) {
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
                        action = "/admin/workspaces/$slug/groups/${group.id}/delete",
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
                action = "/admin/workspaces/$slug/groups/${group.id}/edit",
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
                    table("key-table") {
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
                                    td { span("key-table__name") { +(r?.name ?: "#$rid") } }
                                    td {
                                        span("badge badge--active") { +(r?.scope?.value ?: "?") }
                                    }
                                    td {
                                        form(
                                            action = "/admin/workspaces/$slug/groups/${group.id}/unassign-role",
                                            method = FormMethod.post,
                                        ) {
                                            input(type = InputType.hidden, name = "roleId") {
                                                value = rid.toString()
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
                            action = "/admin/workspaces/$slug/groups/${group.id}/assign-role",
                            method = FormMethod.post,
                        ) {
                            style = "display:flex; align-items:center; gap:8px;"
                            select {
                                classes = setOf("edit-row__field", "edit-row__field--select")
                                name = "roleId"
                                availableRoles.forEach { r ->
                                    option {
                                        value = r.id.toString()
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
                    table("key-table") {
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
                                    td { span("key-table__name") { +u.username } }
                                    td { span("key-table__meta") { +u.email } }
                                    td {
                                        form(
                                            action = "/admin/workspaces/$slug/groups/${group.id}/remove-member",
                                            method = FormMethod.post,
                                        ) {
                                            input(type = InputType.hidden, name = "userId") {
                                                value = u.id.toString()
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
                val nonMembers = allUsers.filter { u -> members.none { it.id == u.id } }
                if (nonMembers.isNotEmpty()) {
                    div("edit-actions") {
                        form(
                            action = "/admin/workspaces/$slug/groups/${group.id}/add-member",
                            method = FormMethod.post,
                        ) {
                            style = "display:flex; align-items:center; gap:8px;"
                            select {
                                classes = setOf("edit-row__field", "edit-row__field--select")
                                name = "userId"
                                nonMembers.forEach { u ->
                                    option {
                                        value = u.id.toString()
                                        +"${u.username} (${u.email})"
                                    }
                                }
                            }
                            button(type = ButtonType.submit) {
                                classes = setOf("btn", "btn--primary", "btn--sm")
                                +"Add Member"
                            }
                        }
                    }
                }
            }
        }
    }
