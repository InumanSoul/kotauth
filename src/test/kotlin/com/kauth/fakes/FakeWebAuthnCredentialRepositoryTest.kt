package com.kauth.fakes

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeWebAuthnCredentialRepositoryTest {
    private val tenantId = TenantId(1)
    private val userId = UserId(42)

    private fun sample(
        credentialId: String = "cred-1",
        name: String = "iPhone",
    ) = WebAuthnCredential(
        userId = userId,
        tenantId = tenantId,
        credentialId = credentialId,
        publicKeyCose = byteArrayOf(1, 2, 3, 4),
        signCounter = 0,
        aaguid = UUID.randomUUID(),
        transports = listOf("internal"),
        name = name,
        backupEligible = true,
        backupState = true,
        createdAt = Instant.now(),
        lastUsedAt = null,
    )

    @Test
    fun `save assigns id and returns stored credential`() {
        val repo = FakeWebAuthnCredentialRepository()
        val saved = repo.save(sample())
        assertEquals(1L, saved.id)
        assertEquals("cred-1", saved.credentialId)
    }

    @Test
    fun `findByCredentialId returns the stored row`() {
        val repo = FakeWebAuthnCredentialRepository()
        repo.save(sample("cred-A"))
        repo.save(sample("cred-B"))
        assertEquals("cred-B", repo.findByCredentialId("cred-B")?.credentialId)
        assertNull(repo.findByCredentialId("cred-Z"))
    }

    @Test
    fun `findByUserId filters by tenant and orders by createdAt`() {
        val repo = FakeWebAuthnCredentialRepository()
        val now = Instant.now()
        repo.save(sample("a").copy(createdAt = now.plusSeconds(2)))
        repo.save(sample("b").copy(createdAt = now.plusSeconds(1)))
        repo.save(sample("c").copy(tenantId = TenantId(99)))
        val result = repo.findByUserId(userId, tenantId).map { it.credentialId }
        assertEquals(listOf("b", "a"), result)
    }

    @Test
    fun `updateCounter mutates signCounter and lastUsedAt`() {
        val repo = FakeWebAuthnCredentialRepository()
        val saved = repo.save(sample())
        val used = Instant.now()
        repo.updateCounter(saved.id!!, 5, used)
        val reloaded = repo.findById(saved.id!!)!!
        assertEquals(5, reloaded.signCounter)
        assertEquals(used, reloaded.lastUsedAt)
    }

    @Test
    fun `rename succeeds when user owns the credential`() {
        val repo = FakeWebAuthnCredentialRepository()
        val saved = repo.save(sample(name = "old"))
        assertTrue(repo.rename(saved.id!!, userId, "new"))
        assertEquals("new", repo.findById(saved.id!!)?.name)
    }

    @Test
    fun `rename fails when cross-user`() {
        val repo = FakeWebAuthnCredentialRepository()
        val saved = repo.save(sample())
        assertEquals(false, repo.rename(saved.id!!, UserId(999), "hack"))
    }

    @Test
    fun `delete removes only if user matches`() {
        val repo = FakeWebAuthnCredentialRepository()
        val saved = repo.save(sample())
        assertEquals(false, repo.delete(saved.id!!, UserId(999)))
        assertEquals(true, repo.delete(saved.id!!, userId))
        assertNull(repo.findById(saved.id!!))
    }

    @Test
    fun `deleteAllByUserId returns count and clears all`() {
        val repo = FakeWebAuthnCredentialRepository()
        repo.save(sample("a"))
        repo.save(sample("b"))
        repo.save(sample("c").copy(tenantId = TenantId(99)))
        assertEquals(2, repo.deleteAllByUserId(userId, tenantId))
        assertEquals(0, repo.findByUserId(userId, tenantId).size)
    }
}
