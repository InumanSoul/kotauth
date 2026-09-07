package com.kauth.fakes

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeUserRepositoryScimTest {
    private val tenantA = TenantId(1)
    private val tenantB = TenantId(2)

    private fun user(
        name: String,
        tenant: TenantId,
        ext: String?,
    ) = User(
        id = UserId(0),
        tenantId = tenant,
        username = name,
        email = "$name@example.com",
        fullName = name,
        passwordHash = User.SENTINEL_PASSWORD_HASH,
        externalId = ext,
    )

    @Test
    fun `findByExternalId returns the matching user`() {
        val repo = FakeUserRepository()
        repo.save(user("ada", tenantA, "ext-1"))

        assertEquals("ada", repo.findByExternalId(tenantA, "ext-1")?.username)
    }

    @Test
    fun `findByExternalId is scoped to the tenant`() {
        val repo = FakeUserRepository()
        repo.save(user("ada", tenantA, "ext-1"))

        // Same external id in a different workspace must not leak across.
        assertNull(repo.findByExternalId(tenantB, "ext-1"))
    }

    @Test
    fun `findByExternalId ignores users that have no external id`() {
        val repo = FakeUserRepository()
        repo.save(user("local", tenantA, null))

        assertNull(repo.findByExternalId(tenantA, "ext-1"))
    }

    @Test
    fun `a renamed user is still found by external id`() {
        val repo = FakeUserRepository()
        val saved = repo.save(user("ada", tenantA, "ext-1"))
        repo.update(saved.copy(username = "ada.lovelace"))

        assertEquals("ada.lovelace", repo.findByExternalId(tenantA, "ext-1")?.username)
    }
}
