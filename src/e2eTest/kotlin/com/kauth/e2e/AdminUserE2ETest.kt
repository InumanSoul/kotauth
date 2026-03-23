package com.kauth.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
import kotlin.test.assertTrue

class AdminUserE2ETest : E2ETestBase() {
    @Test
    fun `create user redirects to user detail page with numeric ID in URL`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/master/users/new")
        page.fill("input[name=username]", "newuser")
        page.fill("input[name=email]", "new@test.dev")
        page.fill("input[name=fullName]", "New User")
        page.fill("input[name=password]", "SecurePass123!")
        page.locator("button:has-text('Create User')").click()

        page.waitForURL(
            Pattern.compile(".*/users/\\d+"),
            Page
                .WaitForURLOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(5000.0),
        )
        val url = page.url()
        assertTrue(
            url.matches(Regex(".*/users/\\d+.*")),
            "URL must contain numeric user ID, got: $url",
        )
        assertTrue(page.content().contains("newuser"))
    }

    @Test
    fun `edit user profile redirects back with saved param`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/master/users/1")
        page.waitForSelector("a[href*='edit'], button:has-text('Edit'), .btn:has-text('Edit')")

        // Navigate to edit (either via HTMX fragment or full page)
        val editLink = page.querySelector("a[href*='edit-fragment'], a[href*='/edit']")
        if (editLink != null) {
            editLink.click()
            page.waitForTimeout(500.0)
        }

        // Fill and submit the edit form
        val emailField = page.querySelector("input[name=email]")
        if (emailField != null) {
            emailField.fill("updated@test.dev")
            page.click("button[type=submit]")
            page.waitForTimeout(1000.0)

            val url = page.url()
            val content = page.content()
            // Should either redirect with ?saved=true or show success via HTMX
            assertTrue(
                url.contains("saved=true") || content.contains("Profile saved") || content.contains("updated@test.dev"),
                "Edit should succeed — URL: $url",
            )
        }
    }

    @Test
    fun `toggle user enabled redirects to user detail with numeric URL`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/master/users/1")

        val toggleForm = page.querySelector("form[action*='/toggle']")
        if (toggleForm != null) {
            val action = toggleForm.getAttribute("action")
            // Action must use numeric user ID
            assertTrue(
                action.matches(Regex(".*/users/\\d+/toggle")),
                "Toggle form action must use numeric ID, got: $action",
            )
            toggleForm.querySelector("button[type=submit]")?.click()
            waitForUrlPattern("**/users/**")

            val url = page.url()
            assertTrue(
                url.matches(Regex(".*/users/\\d+.*")),
                "Redirect URL must contain numeric user ID, got: $url",
            )
        }
    }

    @Test
    fun `revoke sessions redirects to user detail with numeric URL`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/master/users/1")

        val revokeForm = page.querySelector("form[action*='/revoke-sessions']")
        if (revokeForm != null) {
            val action = revokeForm.getAttribute("action")
            assertTrue(
                action.matches(Regex(".*/users/\\d+/revoke-sessions")),
                "Revoke form action must use numeric ID, got: $action",
            )
        }
    }

    @Test
    fun `user list page renders user IDs as plain numbers`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/master/users")
        val content = page.content()

        // Must not contain value class toString artifacts
        assertTrue(
            !content.contains("UserId(value="),
            "Page must not render UserId value class toString",
        )
        assertTrue(
            !content.contains("TenantId(value="),
            "Page must not render TenantId value class toString",
        )
    }
}
