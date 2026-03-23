package com.kauth.e2e

import com.kauth.domain.model.Group
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import org.junit.jupiter.api.Test
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

        navigateSafe("$baseUrl/admin/workspaces/master/audit")
        val content = page.content()

        assertTrue(!content.contains("UserId(value="), "Must not show UserId toString")
        assertTrue(!content.contains("ApplicationId(value="), "Must not show ApplicationId toString")
    }
}
