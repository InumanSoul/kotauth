package com.kauth.domain.service

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserAttribute
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UserAttributeServiceTest {
    private val attributes = FakeUserAttributeRepository()
    private val users = FakeUserRepository()

    private val svc =
        UserAttributeService(
            userAttributeRepository = attributes,
            userRepository = users,
        )

    private val tenantId = TenantId(1)
    private val otherTenantId = TenantId(2)

    private val alice =
        User(
            id = UserId(10),
            tenantId = tenantId,
            username = "alice",
            email = "alice@acme.com",
            fullName = "Alice",
            passwordHash = "hash",
        )

    @BeforeTest
    fun setup() {
        attributes.clear()
        users.clear()
        users.add(alice)
    }

    // =========================================================================
    // list
    // =========================================================================

    @Test
    fun `list - returns empty map when no attributes`() {
        val result = svc.list(alice.id!!, tenantId)
        assertIs<AttributeResult.Success<Map<String, String>>>(result)
        assertTrue(result.value.isEmpty())
    }

    @Test
    fun `list - returns stored attributes`() {
        svc.upsert(alice.id!!, tenantId, "plan", "trial")
        svc.upsert(alice.id!!, tenantId, "trial_ends", "2026-05-21")

        val result = svc.list(alice.id!!, tenantId)
        assertIs<AttributeResult.Success<Map<String, String>>>(result)
        assertEquals(mapOf("plan" to "trial", "trial_ends" to "2026-05-21"), result.value)
    }

    @Test
    fun `list - missing user returns NotFound`() {
        val result = svc.list(UserId(999), tenantId)
        assertIs<AttributeResult.NotFound>(result)
    }

    @Test
    fun `list - wrong tenant returns NotFound`() {
        val result = svc.list(alice.id!!, otherTenantId)
        assertIs<AttributeResult.NotFound>(result)
    }

    // =========================================================================
    // upsert
    // =========================================================================

    @Test
    fun `upsert - creates new attribute`() {
        val result = svc.upsert(alice.id!!, tenantId, "plan", "trial")
        assertIs<AttributeResult.Success<UserAttribute>>(result)
        assertEquals("plan", result.value.key)
        assertEquals("trial", result.value.value)
    }

    @Test
    fun `upsert - overwrites existing attribute`() {
        svc.upsert(alice.id!!, tenantId, "plan", "trial")
        svc.upsert(alice.id!!, tenantId, "plan", "pro")

        val stored = attributes.all().single { it.key == "plan" }
        assertEquals("pro", stored.value)
    }

    @Test
    fun `upsert - blank key is rejected`() {
        val result = svc.upsert(alice.id!!, tenantId, "  ", "value")
        assertIs<AttributeResult.ValidationError>(result)
    }

    @Test
    fun `upsert - key too long is rejected`() {
        val key = "k".repeat(UserAttribute.MAX_KEY_LENGTH + 1)
        val result = svc.upsert(alice.id!!, tenantId, key, "v")
        assertIs<AttributeResult.ValidationError>(result)
    }

    @Test
    fun `upsert - value too long is rejected`() {
        val value = "v".repeat(UserAttribute.MAX_VALUE_LENGTH + 1)
        val result = svc.upsert(alice.id!!, tenantId, "k", value)
        assertIs<AttributeResult.ValidationError>(result)
    }

    @Test
    fun `upsert - exactly at value limit is accepted`() {
        val value = "v".repeat(UserAttribute.MAX_VALUE_LENGTH)
        val result = svc.upsert(alice.id!!, tenantId, "k", value)
        assertIs<AttributeResult.Success<UserAttribute>>(result)
    }

    @Test
    fun `upsert - missing user returns NotFound without writing`() {
        val result = svc.upsert(UserId(999), tenantId, "plan", "trial")
        assertIs<AttributeResult.NotFound>(result)
        assertTrue(attributes.all().isEmpty())
    }

    @Test
    fun `upsert - empty value is allowed`() {
        val result = svc.upsert(alice.id!!, tenantId, "plan", "")
        assertIs<AttributeResult.Success<UserAttribute>>(result)
    }

    // =========================================================================
    // delete
    // =========================================================================

    @Test
    fun `delete - removes existing attribute`() {
        svc.upsert(alice.id!!, tenantId, "plan", "trial")
        val result = svc.delete(alice.id!!, tenantId, "plan")
        assertIs<AttributeResult.Success<Unit>>(result)
        assertTrue(attributes.all().isEmpty())
    }

    @Test
    fun `delete - missing key succeeds silently`() {
        val result = svc.delete(alice.id!!, tenantId, "nonexistent")
        assertIs<AttributeResult.Success<Unit>>(result)
    }

    @Test
    fun `delete - missing user returns NotFound`() {
        val result = svc.delete(UserId(999), tenantId, "plan")
        assertIs<AttributeResult.NotFound>(result)
    }

    // =========================================================================
    // tenant isolation
    // =========================================================================

    @Test
    fun `attributes from different tenants do not bleed`() {
        val bob =
            User(
                id = UserId(20),
                tenantId = otherTenantId,
                username = "bob",
                email = "bob@other.com",
                fullName = "Bob",
                passwordHash = "hash",
            )
        users.add(bob)

        svc.upsert(alice.id!!, tenantId, "plan", "trial")
        svc.upsert(bob.id!!, otherTenantId, "plan", "pro")

        val aliceAttrs = svc.list(alice.id!!, tenantId)
        val bobAttrs = svc.list(bob.id!!, otherTenantId)

        assertIs<AttributeResult.Success<Map<String, String>>>(aliceAttrs)
        assertIs<AttributeResult.Success<Map<String, String>>>(bobAttrs)
        assertEquals("trial", aliceAttrs.value["plan"])
        assertEquals("pro", bobAttrs.value["plan"])
    }
}
