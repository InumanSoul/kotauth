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
        adminShell(
            pageTitle = "Roles — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "roles",
            loggedInAs = loggedInAs,
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"Roles" }
            }
            div("page-header") {
                div {
                    p("page-title") { +"Roles" }
                    p(
                        "page-subtitle",
                    ) { +"${roles.size} role${if (roles.size != 1) "s" else ""} in this workspace" }
                }
                a(
                    href = "/admin/workspaces/${workspace.slug}/roles/create",
                    classes = "btn",
                ) { +"+ Create Role" }
            }

            div("card") {
                if (roles.isEmpty()) {
                    div("empty-state") {
                        div("empty-state-icon") { +"◎" }
                        p("empty-state-text") { +"No roles defined yet." }
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Name" }
                                th { +"Scope" }
                                th { +"Description" }
                                th { +"Composite" }
                                th { +"" }
                            }
                        }
                        tbody {
                            roles.forEach { role ->
                                tr {
                                    td { span("td-code") { +(role.name) } }
                                    td {
                                        val isWorkspace = role.scope.value == "tenant"
                                        span("badge badge-${if (isWorkspace) "green" else "blue"}") {
                                            +(if (isWorkspace) "workspace" else "application")
                                        }
                                    }
                                    td { +(role.description ?: "—") }
                                    td {
                                        +(if (role.childRoleIds.isNotEmpty()) "${role.childRoleIds.size} children" else "—")
                                    }
                                    td {
                                        a(
                                            href = "/admin/workspaces/${workspace.slug}/roles/${role.id}",
                                            classes = "btn btn-ghost btn-sm",
                                        ) { +"Open →" }
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
        adminShell(
            pageTitle = "New Role — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "roles",
            loggedInAs = loggedInAs,
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}/roles") { +"Roles" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"New Role" }
            }
            div("page-header") {
                div {
                    p("page-title") { +"Create Role" }
                    p("page-subtitle") {
                        +"Add a role to the "
                        strong { +workspace.displayName }
                        +" workspace."
                    }
                }
            }
            if (error != null) {
                div("alert alert-error alert--constrained") {
                    +error
                }
            }
            div("form-card") {
                form(
                    action = "/admin/workspaces/${workspace.slug}/roles",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    div("field") {
                        label {
                            htmlFor = "roleName"
                            +"Name"
                        }
                        input(type = InputType.text, name = "name") {
                            id = "roleName"
                            required = true
                            placeholder = "e.g. admin, editor, viewer"
                            attributes["pattern"] = "[a-zA-Z0-9._-]+"
                        }
                        p("field-hint") { +"Letters, digits, dots, underscores, hyphens only." }
                    }
                    div("field") {
                        label {
                            htmlFor = "roleDesc"
                            +"Description (optional)"
                        }
                        input(type = InputType.text, name = "description") {
                            id = "roleDesc"
                            placeholder = "Short description of what this role grants"
                        }
                    }
                    div("field") {
                        label {
                            htmlFor = "roleScope"
                            +"Scope"
                        }
                        select {
                            name = "scope"
                            id = "roleScope"
                            attributes["onchange"] =
                                "document.getElementById('appField').style.display=this.value==='client'?'block':'none'"
                            option {
                                value = "tenant"
                                +"Workspace (realm-level)"
                            }
                            option {
                                value = "client"
                                +"Application (app-scoped)"
                            }
                        }
                        p("field-hint") {
                            +"Workspace roles apply across the entire workspace. Application roles are scoped to a specific app."
                        }
                    }
                    div("field") {
                        id = "appField"
                        style = "display:none;"
                        label {
                            htmlFor = "clientId"
                            +"Application"
                        }
                        if (apps.isEmpty()) {
                            p("field-hint") { +"No applications in this workspace yet. Create one first." }
                        } else {
                            select {
                                name = "clientId"
                                id = "clientId"
                                option {
                                    value = ""
                                    +"— select application —"
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
                    div("form-actions") {
                        button(type = ButtonType.submit, classes = "btn") { +"Create Role" }
                        a("/admin/workspaces/${workspace.slug}/roles", classes = "btn btn-ghost") { +"Cancel" }
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
        adminShell(
            pageTitle = "${role.name} — Roles",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "roles",
            loggedInAs = loggedInAs,
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}/roles") { +"Roles" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +role.name }
            }
            div("page-header") {
                div {
                    p("page-title") { +role.name }
                    p("page-subtitle") { +"${role.scope.value} role · ${role.description ?: "no description"}" }
                }
                form(
                    action = "/admin/workspaces/${workspace.slug}/roles/${role.id}/delete",
                    method = FormMethod.post,
                    classes = "inline-form",
                ) {
                    button(type = ButtonType.submit, classes = "btn btn-ghost btn-sm") {
                        attributes["onclick"] = "return confirm('Delete role ${role.name}?')"
                        +"Delete"
                    }
                }
            }

            // Edit name/description
            div("form-card form-card--wide card--spaced") {
                form(
                    action = "/admin/workspaces/${workspace.slug}/roles/${role.id}/edit",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    p("form-section-title") { +"Edit Role" }
                    div("field") {
                        label {
                            htmlFor = "roleName"
                            +"Name"
                        }
                        input(type = InputType.text, name = "name") {
                            id = "roleName"
                            required = true
                            value =
                                role.name
                        }
                    }
                    div("field") {
                        label {
                            htmlFor = "roleDesc"
                            +"Description"
                        }
                        input(type = InputType.text, name = "description") {
                            id = "roleDesc"
                            value =
                                role.description ?: ""
                        }
                    }
                    div("form-actions") {
                        button(type = ButtonType.submit, classes = "btn btn-sm") { +"Save" }
                    }
                }
            }

            // Composite children
            div("card card--spaced") {
                p("form-section-title") { +"Composite Children" }
                if (role.childRoleIds.isEmpty()) {
                    p("card-empty-msg") {
                        +"No child roles."
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Child Role" }
                                th { +"" }
                            }
                        }
                        tbody {
                            role.childRoleIds.forEach { childId ->
                                val child = allRoles.find { it.id == childId }
                                tr {
                                    td { +(child?.name ?: "#$childId") }
                                    td {
                                        form(
                                            action = "/admin/workspaces/${workspace.slug}/roles/${role.id}/remove-child",
                                            method = FormMethod.post,
                                            classes = "inline-form",
                                        ) {
                                            input(type = InputType.hidden, name = "childRoleId") {
                                                value =
                                                    childId.toString()
                                            }
                                            button(
                                                type = ButtonType.submit,
                                                classes = "btn btn-ghost btn-sm",
                                            ) { +"Remove" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Add child form
                val availableChildren = allRoles.filter { it.id != role.id && it.id !in role.childRoleIds }
                if (availableChildren.isNotEmpty()) {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/roles/${role.id}/children",
                        method = FormMethod.post,
                        classes = "card-add-row",
                    ) {
                        select {
                            name = "childRoleId"
                            availableChildren.forEach { r ->
                                option {
                                    value = r.id.toString()
                                    +r.name
                                }
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn btn-sm") { +"Add Child" }
                    }
                }
            }

            // Assign user
            div("card") {
                p("form-section-title") { +"Assigned Users" }
                form(
                    action = "/admin/workspaces/${workspace.slug}/roles/${role.id}/assign-user",
                    method = FormMethod.post,
                    classes = "card-add-row",
                ) {
                    select {
                        name = "userId"
                        allUsers.forEach { u ->
                            option {
                                value = u.id.toString()
                                +"${u.username} (${u.email})"
                            }
                        }
                    }
                    button(type = ButtonType.submit, classes = "btn btn-sm") { +"Assign" }
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
        adminShell(
            pageTitle = "Groups — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "groups",
            loggedInAs = loggedInAs,
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"Groups" }
            }
            div("page-header") {
                div {
                    p("page-title") { +"Groups" }
                    p(
                        "page-subtitle",
                    ) { +"${groups.size} group${if (groups.size != 1) "s" else ""} in this workspace" }
                }
                a(
                    href = "/admin/workspaces/${workspace.slug}/groups/create",
                    classes = "btn",
                ) { +"+ Create Group" }
            }

            div("card") {
                if (groups.isEmpty()) {
                    div("empty-state") {
                        div("empty-state-icon") { +"◫" }
                        p("empty-state-text") { +"No groups defined yet." }
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Name" }
                                th { +"Parent" }
                                th { +"Roles" }
                                th { +"Description" }
                                th { +"" }
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
                                    td { span("td-code") { +group.name } }
                                    td { +(parent?.name ?: "—") }
                                    td { +(if (roleNames.isNotEmpty()) roleNames.joinToString(", ") else "—") }
                                    td { +(group.description ?: "—") }
                                    td {
                                        a(
                                            href = "/admin/workspaces/${workspace.slug}/groups/${group.id}",
                                            classes = "btn btn-ghost btn-sm",
                                        ) { +"Open →" }
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
        adminShell(
            pageTitle = "New Group — ${workspace.displayName}",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "groups",
            loggedInAs = loggedInAs,
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}/groups") { +"Groups" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"New Group" }
            }
            div("page-header") {
                div {
                    p("page-title") { +"Create Group" }
                    p("page-subtitle") {
                        +"Add a group to the "
                        strong { +workspace.displayName }
                        +" workspace."
                    }
                }
            }
            if (error != null) {
                div("alert alert-error alert--constrained") {
                    +error
                }
            }
            div("form-card") {
                form(
                    action = "/admin/workspaces/${workspace.slug}/groups",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    div("field") {
                        label {
                            htmlFor = "groupName"
                            +"Name"
                        }
                        input(type = InputType.text, name = "name") {
                            id = "groupName"
                            required = true
                            placeholder = "e.g. engineering, marketing, ops"
                        }
                    }
                    div("field") {
                        label {
                            htmlFor = "groupDesc"
                            +"Description (optional)"
                        }
                        input(type = InputType.text, name = "description") {
                            id = "groupDesc"
                            placeholder = "What this group represents"
                        }
                    }
                    div("field") {
                        label {
                            htmlFor = "parentGroup"
                            +"Parent Group (optional)"
                        }
                        select {
                            name = "parentGroupId"
                            id = "parentGroup"
                            option {
                                value = ""
                                +"— None (top-level) —"
                            }
                            groups.forEach { g ->
                                option {
                                    value = g.id.toString()
                                    +g.name
                                }
                            }
                        }
                        p("field-hint") { +"Nested groups inherit the roles assigned to their parent." }
                    }
                    div("form-actions") {
                        button(type = ButtonType.submit, classes = "btn") { +"Create Group" }
                        a("/admin/workspaces/${workspace.slug}/groups", classes = "btn btn-ghost") { +"Cancel" }
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
        adminShell(
            pageTitle = "${group.name} — Groups",
            activeRail = "directory",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            activeAppSection = "groups",
            loggedInAs = loggedInAs,
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}/groups") { +"Groups" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +group.name }
            }
            div("page-header") {
                div {
                    p("page-title") { +group.name }
                    p("page-subtitle") {
                        val parent = allGroups.find { it.id == group.parentGroupId }
                        +(if (parent != null) "Child of ${parent.name}" else "Top-level group")
                        +" · ${group.description ?: "no description"}"
                    }
                }
                form(
                    action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/delete",
                    method = FormMethod.post,
                    classes = "inline-form",
                ) {
                    button(type = ButtonType.submit, classes = "btn btn-ghost btn-sm") {
                        attributes["onclick"] = "return confirm('Delete group ${group.name}?')"
                        +"Delete"
                    }
                }
            }

            // Edit name/description
            div("form-card form-card--wide card--spaced") {
                form(
                    action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/edit",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    p("form-section-title") { +"Edit Group" }
                    div("field") {
                        label {
                            htmlFor = "gName"
                            +"Name"
                        }
                        input(type = InputType.text, name = "name") {
                            id = "gName"
                            required = true
                            value = group.name
                        }
                    }
                    div("field") {
                        label {
                            htmlFor = "gDesc"
                            +"Description"
                        }
                        input(type = InputType.text, name = "description") {
                            id = "gDesc"
                            value =
                                group.description ?: ""
                        }
                    }
                    div("form-actions") {
                        button(type = ButtonType.submit, classes = "btn btn-sm") { +"Save" }
                    }
                }
            }

            // Assigned roles
            div("card card--spaced") {
                p("form-section-title") { +"Assigned Roles" }
                if (group.roleIds.isEmpty()) {
                    p("card-empty-msg") {
                        +"No roles assigned."
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Role" }
                                th { +"Scope" }
                                th { +"" }
                            }
                        }
                        tbody {
                            group.roleIds.forEach { rid ->
                                val r = allRoles.find { it.id == rid }
                                tr {
                                    td { +(r?.name ?: "#$rid") }
                                    td { span("badge badge-green") { +(r?.scope?.value ?: "?") } }
                                    td {
                                        form(
                                            action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/unassign-role",
                                            method = FormMethod.post,
                                            classes = "inline-form",
                                        ) {
                                            input(
                                                type = InputType.hidden,
                                                name = "roleId",
                                            ) { value = rid.toString() }
                                            button(
                                                type = ButtonType.submit,
                                                classes = "btn btn-ghost btn-sm",
                                            ) { +"Remove" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val availableRoles = allRoles.filter { it.id !in group.roleIds }
                if (availableRoles.isNotEmpty()) {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/assign-role",
                        method = FormMethod.post,
                        classes = "card-add-row",
                    ) {
                        select {
                            name = "roleId"
                            availableRoles.forEach { r ->
                                option {
                                    value = r.id.toString()
                                    +r.name
                                }
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn btn-sm") { +"Assign Role" }
                    }
                }
            }

            // Members
            div("card") {
                p("form-section-title") { +"Members (${members.size})" }
                if (members.isEmpty()) {
                    p("card-empty-msg") {
                        +"No members."
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Username" }
                                th { +"Email" }
                                th { +"" }
                            }
                        }
                        tbody {
                            members.forEach { u ->
                                tr {
                                    td { span("td-code") { +u.username } }
                                    td { +u.email }
                                    td {
                                        form(
                                            action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/remove-member",
                                            method = FormMethod.post,
                                            classes = "inline-form",
                                        ) {
                                            input(type = InputType.hidden, name = "userId") {
                                                value =
                                                    u.id.toString()
                                            }
                                            button(
                                                type = ButtonType.submit,
                                                classes = "btn btn-ghost btn-sm",
                                            ) { +"Remove" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Add member
                val nonMembers = allUsers.filter { u -> members.none { it.id == u.id } }
                if (nonMembers.isNotEmpty()) {
                    form(
                        action = "/admin/workspaces/${workspace.slug}/groups/${group.id}/add-member",
                        method = FormMethod.post,
                        classes = "card-add-row",
                    ) {
                        select {
                            name = "userId"
                            nonMembers.forEach { u ->
                                option {
                                    value = u.id.toString()
                                    +"${u.username} (${u.email})"
                                }
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn btn-sm") { +"Add Member" }
                    }
                }
            }
        }
    }
