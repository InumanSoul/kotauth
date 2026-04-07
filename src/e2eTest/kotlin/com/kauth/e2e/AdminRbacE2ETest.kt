package com.kauth.e2e

import com.kauth.domain.model.Group
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminRbacE2ETest : E2ETestBase() {
    @Test
    fun `create role redirects with numeric role ID in URL`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/master/roles/create")
        page.fill("input[name=name]", "test-role")
        page.fill("input[name=description]", "E2E test role")
        page.locator("button[type=submit][form=create-role-form]").click()

        waitForUrlPattern("**/roles")
        val url = page.url()
        assertTrue(
            !url.contains("RoleId("),
            "URL must not contain RoleId value class toString, got: $url",
        )
    }

    @Test
    fun `role detail page renders IDs as plain numbers`() {
        val saved =
            roleRepo.add(
                Role(tenantId = TenantId(1), name = "viewer", description = "View only", scope = RoleScope.TENANT),
            )
        val roleId = saved.id

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/master/roles/${roleId?.value}")

        val content = page.content()
        assertTrue(
            !content.contains("RoleId(value="),
            "Page must not render RoleId value class toString",
        )
        assertTrue(
            !content.contains("UserId(value="),
            "Page must not render UserId value class toString",
        )
        assertTrue(
            !content.contains("ApplicationId(value="),
            "Page must not render ApplicationId value class toString",
        )

        // Check form hidden inputs contain numeric values
        val hiddenInputs = page.querySelectorAll("input[type=hidden]")
        hiddenInputs.forEach { input ->
            val value = input.getAttribute("value") ?: ""
            if (value.isNotBlank()) {
                assertTrue(
                    !value.contains("Id(value="),
                    "Hidden input value must be numeric, got: $value",
                )
            }
        }
    }

    @Test
    fun `create group redirects with numeric group ID in URL`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/master/groups/create")
        page.fill("input[name=name]", "test-group")
        page.fill("input[name=description]", "E2E test group")
        page.locator("button[type=submit][form=create-group-form]").click()

        waitForUrlPattern("**/groups")
        val url = page.url()
        assertTrue(
            !url.contains("GroupId("),
            "URL must not contain GroupId value class toString, got: $url",
        )
    }

    @Test
    fun `group detail page renders IDs as plain numbers`() {
        val saved =
            groupRepo.add(
                Group(tenantId = TenantId(1), name = "devs", description = "Dev team"),
            )
        val groupId = saved.id

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/master/groups/${groupId?.value}")

        val content = page.content()
        assertTrue(
            !content.contains("GroupId(value="),
            "Page must not render GroupId value class toString",
        )
        assertTrue(
            !content.contains("UserId(value="),
            "Page must not render UserId value class toString",
        )
        assertTrue(
            !content.contains("RoleId(value="),
            "Page must not render RoleId value class toString",
        )

        val hiddenInputs = page.querySelectorAll("input[type=hidden]")
        hiddenInputs.forEach { input ->
            val value = input.getAttribute("value") ?: ""
            if (value.isNotBlank()) {
                assertTrue(
                    !value.contains("Id(value="),
                    "Hidden input value must be numeric, got: $value",
                )
            }
        }
    }

    @Test
    fun `session list page does not show value class toString`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/master/sessions")
        val content = page.content()

        assertTrue(!content.contains("UserId(value="), "Must not show UserId toString")
        assertTrue(!content.contains("ApplicationId(value="), "Must not show ApplicationId toString")
        assertTrue(!content.contains("SessionId(value="), "Must not show SessionId toString")
    }

    @Test
    fun `audit log page does not show value class toString`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/master/logs")
        val content = page.content()

        assertTrue(content.contains("Audit") || content.contains("Log"), "Must reach the audit log page")
        assertTrue(!content.contains("UserId(value="), "Must not show UserId toString")
        assertTrue(!content.contains("ApplicationId(value="), "Must not show ApplicationId toString")
    }

    @Test
    fun `role detail child role forms use plain int IDs`() {
        val parentRole =
            roleRepo.add(
                Role(
                    tenantId = TenantId(1),
                    name = "manager",
                    description = "Manager role",
                    scope = RoleScope.TENANT,
                ),
            )
        val childRole =
            roleRepo.add(
                Role(
                    tenantId = TenantId(1),
                    name = "viewer",
                    description = "View only",
                    scope = RoleScope.TENANT,
                ),
            )
        // Update parent to include child in childRoleIds so the view renders it
        roleRepo.update(parentRole.copy(childRoleIds = listOf(childRole.id!!)))

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/master/roles/${parentRole.id?.value}")

        val content = page.content()
        assertFalse(content.contains("RoleId(value="), "Must not render RoleId value class toString")

        // Check remove-child form hidden input
        val childInputs = page.querySelectorAll("input[type=hidden][name=childRoleId]")
        childInputs.forEach { input ->
            val value = input.getAttribute("value") ?: ""
            assertTrue(
                value.toIntOrNull() != null,
                "childRoleId hidden input must be a plain integer, got: $value",
            )
        }

        // Check add-child select options
        val childOptions = page.querySelectorAll("select[name=childRoleId] option")
        childOptions.forEach { opt ->
            val value = opt.getAttribute("value") ?: ""
            if (value.isNotBlank()) {
                assertTrue(
                    value.toIntOrNull() != null,
                    "childRoleId select option must be a plain integer, got: $value",
                )
            }
        }
    }

    @Test
    fun `role detail has entity picker for user assignment`() {
        val role =
            roleRepo.add(
                Role(
                    tenantId = TenantId(1),
                    name = "editor",
                    description = "Editor role",
                    scope = RoleScope.TENANT,
                ),
            )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/master/roles/${role.id?.value}")

        val content = page.content()
        assertFalse(content.contains("RoleId(value="), "Must not render RoleId value class toString")

        // Entity picker should be present instead of the old <select>
        val picker = page.querySelector("[data-entity-picker]")
        assertTrue(picker != null, "Entity picker should be present for user assignment")

        // The picker input should have hx-get pointing to the search endpoint
        val pickerInput = page.querySelector(".entity-picker__input")
        if (pickerInput != null) {
            val hxGet = pickerInput.getAttribute("hx-get") ?: ""
            assertTrue(
                hxGet.contains("/search-users"),
                "Picker input hx-get should point to search-users endpoint, got: $hxGet",
            )
        }
    }

    @Test
    fun `group detail assign-role and add-member forms use plain int IDs`() {
        val role =
            roleRepo.add(
                Role(
                    tenantId = TenantId(1),
                    name = "dev-role",
                    description = "Developer role",
                    scope = RoleScope.TENANT,
                ),
            )
        val group =
            groupRepo.add(
                Group(
                    tenantId = TenantId(1),
                    name = "developers",
                    description = "Dev team",
                    roleIds = listOf(role.id!!),
                ),
            )
        userRepo.add(
            User(
                id = UserId(20),
                tenantId = TenantId(1),
                username = "dev-user",
                email = "dev@example.com",
                fullName = "Dev User",
                passwordHash = hasher.hash("password"),
                enabled = true,
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/master/groups/${group.id?.value}")

        val content = page.content()
        assertFalse(content.contains("GroupId(value="), "Must not render GroupId value class toString")
        assertFalse(content.contains("RoleId(value="), "Must not render RoleId value class toString")
        assertFalse(content.contains("UserId(value="), "Must not render UserId value class toString")

        // Check unassign-role hidden inputs
        val roleInputs = page.querySelectorAll("input[type=hidden][name=roleId]")
        roleInputs.forEach { input ->
            val value = input.getAttribute("value") ?: ""
            assertTrue(
                value.toIntOrNull() != null,
                "roleId hidden input must be a plain integer, got: $value",
            )
        }

        // Entity picker should be present for member assignment instead of the old <select>
        val memberPicker = page.querySelector("[data-entity-picker]")
        assertTrue(memberPicker != null, "Entity picker should be present for member assignment")

        // Check form actions use plain ints
        val groupForms = page.querySelectorAll("form[action*='/groups/']")
        groupForms.forEach { form ->
            val action = form.getAttribute("action") ?: ""
            assertFalse(
                action.contains("GroupId(") || action.contains("RoleId(") || action.contains("UserId("),
                "Group form action must not contain value class toString, got: $action",
            )
        }
    }
}
