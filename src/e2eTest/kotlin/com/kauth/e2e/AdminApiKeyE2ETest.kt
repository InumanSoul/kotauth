package com.kauth.e2e

import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminApiKeyE2ETest : E2ETestBase() {
    private val workspace =
        Tenant(
            id = TenantId(2),
            slug = "apikey-ws",
            displayName = "API Key Workspace",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    @BeforeEach
    fun seedWorkspace() {
        tenantRepo.add(workspace)
    }

    @Test
    fun `create api key shows raw key on success`() {
        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/apikey-ws/settings/api-keys/new")

        page.fill("input[name=name]", "CI Pipeline Key")
        page.locator("input[name=scopes][value='${ApiScope.USERS_READ}']").check()
        page.locator("button[type=submit][form=create-api-key-form]").click()

        waitForUrlPattern("**/settings/api-keys**")
        val content = page.content()
        assertTrue(
            content.contains("kauth_"),
            "After creation, page must display the raw API key (starts with kauth_)",
        )
    }

    @Test
    fun `api key list page does not leak value class toString`() {
        apiKeyRepo.save(
            ApiKey(
                tenantId = TenantId(2),
                name = "Test Key",
                keyPrefix = "kauth_apikey_",
                keyHash = "fake-hash-1",
                scopes = listOf(ApiScope.USERS_READ, ApiScope.ROLES_READ),
                enabled = true,
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/apikey-ws/settings/api-keys")

        val content = page.content()
        assertFalse(
            content.contains("TenantId(value="),
            "API keys page must not render TenantId value class toString",
        )
        assertTrue(
            content.contains("Test Key"),
            "API keys page must display the seeded key name",
        )
    }

    @Test
    fun `revoke and delete form actions use plain int keyId`() {
        apiKeyRepo.save(
            ApiKey(
                tenantId = TenantId(2),
                name = "Revocable Key",
                keyPrefix = "kauth_apikey_",
                keyHash = "fake-hash-2",
                scopes = listOf(ApiScope.USERS_READ),
                enabled = true,
            ),
        )

        loginAsAdmin()
        navigateSafe("$baseUrl/admin/workspaces/apikey-ws/settings/api-keys")

        val forms = page.querySelectorAll("form[action*='/api-keys/']")
        forms.forEach { form ->
            val action = form.getAttribute("action") ?: ""
            if (action.contains("/revoke") || action.contains("/delete")) {
                assertFalse(
                    action.contains("ApiKey(") || action.contains("TenantId("),
                    "Form action must use plain int ID, got: $action",
                )
                val idSegment = action.substringAfter("/api-keys/").substringBefore("/")
                assertTrue(
                    idSegment.toIntOrNull() != null,
                    "keyId in form action must be a plain integer, got: $idSegment",
                )
            }
        }
    }
}
