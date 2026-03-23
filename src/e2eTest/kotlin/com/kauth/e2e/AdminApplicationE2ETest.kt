package com.kauth.e2e

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.TenantId
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
import kotlin.test.assertTrue

class AdminApplicationE2ETest : E2ETestBase() {
    @Test
    fun `create application redirects to app detail with clientId in URL`() {
        loginAsAdmin()

        navigateSafe("$baseUrl/admin/workspaces/master/applications/new")
        page.fill("input[name=clientId]", "test-app")
        page.fill("input[name=name]", "Test Application")
        page.locator("button[type=submit][form=create-app-form]").click()

        page.waitForURL(
            Pattern.compile(".*/applications/test-app"),
            Page
                .WaitForURLOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(5000.0),
        )
        val url = page.url()
        assertTrue(
            url.endsWith("/applications/test-app"),
            "URL must end with clientId string, got: $url",
        )
    }

    @Test
    fun `application detail page does not leak value class toString`() {
        appRepo.add(
            Application(
                id = ApplicationId(10),
                tenantId = TenantId(1),
                clientId = "my-spa",
                name = "My SPA",
                description = "Single page app",
                accessType = AccessType.PUBLIC,
                enabled = true,
                redirectUris = listOf("http://localhost:3000/callback"),
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/master/applications/my-spa")

        val content = page.content()
        assertTrue(
            !content.contains("ApplicationId(value="),
            "Page must not render ApplicationId value class toString",
        )
        assertTrue(
            !content.contains("TenantId(value="),
            "Page must not render TenantId value class toString",
        )
    }

    @Test
    fun `application detail hidden inputs contain plain values`() {
        appRepo.add(
            Application(
                id = ApplicationId(10),
                tenantId = TenantId(1),
                clientId = "my-api",
                name = "My API",
                description = null,
                accessType = AccessType.CONFIDENTIAL,
                enabled = true,
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/master/applications/my-api")

        val hiddenInputs = page.querySelectorAll("input[type=hidden]")
        hiddenInputs.forEach { input ->
            val value = input.getAttribute("value") ?: ""
            if (value.isNotBlank()) {
                assertTrue(
                    !value.contains("Id(value="),
                    "Hidden input value must not contain value class toString, got: $value",
                )
            }
        }
    }

    @Test
    fun `toggle application form action uses string clientId not numeric ID`() {
        appRepo.add(
            Application(
                id = ApplicationId(10),
                tenantId = TenantId(1),
                clientId = "toggle-app",
                name = "Toggle App",
                description = null,
                accessType = AccessType.PUBLIC,
                enabled = true,
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/master/applications/toggle-app")

        val toggleForm = page.querySelector("form[action*='/toggle']")
        if (toggleForm != null) {
            val action = toggleForm.getAttribute("action")
            assertTrue(
                action.contains("/applications/toggle-app/toggle"),
                "Toggle action must use string clientId, got: $action",
            )
            assertTrue(
                !action.contains("ApplicationId("),
                "Toggle action must not contain ApplicationId toString, got: $action",
            )
        }
    }
}
