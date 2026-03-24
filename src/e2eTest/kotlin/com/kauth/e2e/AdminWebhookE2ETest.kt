package com.kauth.e2e

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.WebhookEndpoint
import com.kauth.domain.model.WebhookEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminWebhookE2ETest : E2ETestBase() {
    private val workspace =
        Tenant(
            id = TenantId(2),
            slug = "webhook-ws",
            displayName = "Webhook Workspace",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    @BeforeEach
    fun seedWorkspace() {
        tenantRepo.add(workspace)
    }

    @Test
    fun `create webhook shows secret on success`() {
        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/webhook-ws/settings/webhooks/new")

        page.fill("input[name=url]", "https://example.com/hook")
        page.locator("input[name=events][value='${WebhookEvent.USER_CREATED}']").check()
        page.locator("button[type=submit][form=create-webhook-form]").click()

        waitForUrlPattern("**/settings/webhooks**")
        val content = page.content()
        assertTrue(
            content.contains("example.com") || content.contains("secret") || content.contains("Secret"),
            "After creation, page must display the webhook endpoint or signing secret",
        )
    }

    @Test
    fun `webhook list page does not leak value class toString`() {
        webhookEndpointRepo.add(
            WebhookEndpoint(
                id = 10,
                tenantId = TenantId(2),
                url = "https://hooks.example.com/receiver",
                secret = "fake-secret-abc",
                events = setOf(WebhookEvent.USER_CREATED, WebhookEvent.LOGIN_SUCCESS),
                description = "Test Endpoint",
                enabled = true,
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/webhook-ws/settings/webhooks")

        val content = page.content()
        assertFalse(
            content.contains("TenantId(value="),
            "Webhooks page must not render TenantId value class toString",
        )
        assertTrue(
            content.contains("hooks.example.com") || content.contains("Test Endpoint"),
            "Webhooks page must display the seeded endpoint",
        )
    }

    @Test
    fun `toggle and delete form actions use plain int endpointId`() {
        webhookEndpointRepo.add(
            WebhookEndpoint(
                id = 10,
                tenantId = TenantId(2),
                url = "https://hooks.example.com/receiver",
                secret = "fake-secret-abc",
                events = setOf(WebhookEvent.USER_CREATED),
                description = "Toggle Test",
                enabled = true,
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/webhook-ws/settings/webhooks")

        val forms = page.querySelectorAll("form[action*='/webhooks/']")
        forms.forEach { form ->
            val action = form.getAttribute("action") ?: ""
            if (action.contains("/toggle") || action.contains("/delete")) {
                assertFalse(
                    action.contains("WebhookEndpoint(") || action.contains("TenantId("),
                    "Form action must not contain value class toString, got: $action",
                )
                val idSegment = action.substringAfter("/webhooks/").substringBefore("/")
                assertTrue(
                    idSegment.toIntOrNull() != null,
                    "endpointId in form action must be a plain integer, got: $idSegment",
                )
            }
        }
    }
}
