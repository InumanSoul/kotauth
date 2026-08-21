package com.kauth.fakes

import com.kauth.domain.model.GrantType
import com.kauth.domain.model.TenantId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeApplicationRepositoryTest {
    @Test
    fun `create persists grant types and the secret hash`() {
        val repo = FakeApplicationRepository()
        val app =
            repo.create(
                tenantId = TenantId(1),
                clientId = "m2m",
                name = "M2M",
                description = null,
                accessType = "confidential",
                redirectUris = emptyList(),
                grantTypes = setOf(GrantType.CLIENT_CREDENTIALS),
                clientSecretHash = "hash-1",
                audience = null,
            )

        assertEquals(setOf(GrantType.CLIENT_CREDENTIALS), app.grantTypes)
        assertEquals("hash-1", repo.findClientSecretHash(app.id))
        assertEquals(setOf(GrantType.CLIENT_CREDENTIALS), repo.findById(app.id)!!.grantTypes)
    }

    @Test
    fun `create with a null secret hash leaves the secret unset`() {
        val repo = FakeApplicationRepository()
        val app =
            repo.create(
                tenantId = TenantId(1),
                clientId = "spa",
                name = "SPA",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/cb"),
                grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
                clientSecretHash = null,
                audience = null,
            )

        assertNull(repo.findClientSecretHash(app.id))
    }

    @Test
    fun `update replaces the grant set`() {
        val repo = FakeApplicationRepository()
        val app =
            repo.create(
                tenantId = TenantId(1),
                clientId = "web",
                name = "Web",
                description = null,
                accessType = "confidential",
                redirectUris = listOf("https://example.com/cb"),
                grantTypes = setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN),
                clientSecretHash = null,
                audience = null,
            )

        val updated =
            repo.update(
                appId = app.id,
                name = "Web",
                description = null,
                accessType = "confidential",
                redirectUris = listOf("https://example.com/cb"),
                grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
                launcherUrl = null,
                iconUrl = null,
                launcherVisible = true,
                launcherDisplayOrder = 0,
                audience = null,
            )

        assertEquals(setOf(GrantType.AUTHORIZATION_CODE), updated.grantTypes)
    }
}
