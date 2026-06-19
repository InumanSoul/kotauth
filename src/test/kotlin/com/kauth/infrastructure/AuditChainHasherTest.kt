package com.kauth.infrastructure

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuditChainHasherTest {
    private val secret = "this-is-a-test-secret-key-for-audit-chain"
    private val hasher = AuditChainHasher(secret)

    private val sampleTime = OffsetDateTime.of(2026, 6, 19, 12, 0, 0, 0, ZoneOffset.UTC)

    private fun canonical(
        id: Int = 1,
        tenantId: Int? = 1,
        userId: Int? = 10,
        clientId: Int? = null,
        eventType: String = "LOGIN_SUCCESS",
        ipAddress: String? = "127.0.0.1",
        userAgent: String? = "TestAgent/1.0",
        createdAt: OffsetDateTime = sampleTime,
        detailsJson: String? = null,
        prevHash: ByteArray? = null,
    ) = hasher.canonicalize(
        id,
        tenantId,
        userId,
        clientId,
        eventType,
        ipAddress,
        userAgent,
        createdAt,
        detailsJson,
        prevHash,
    )

    @Test
    fun `chainKeyId is exactly 8 hex characters`() {
        val id = hasher.chainKeyId
        assertEquals(8, id.length)
        assertTrue(id.all { it.isDigit() || it in 'a'..'f' }, "chainKeyId must be lowercase hex: $id")
    }

    @Test
    fun `same inputs produce identical canonical bytes`() {
        val c1 = canonical()
        val c2 = canonical()
        assertTrue(c1.contentEquals(c2), "Canonical form must be deterministic")
    }

    @Test
    fun `same inputs produce identical HMAC`() {
        val h1 = hasher.hmac(canonical())
        val h2 = hasher.hmac(canonical())
        assertTrue(h1.contentEquals(h2), "HMAC must be deterministic")
    }

    @Test
    fun `changing id changes HMAC`() {
        val h1 = hasher.hmac(canonical(id = 1))
        val h2 = hasher.hmac(canonical(id = 2))
        assertFalse(h1.contentEquals(h2))
    }

    @Test
    fun `changing tenantId changes HMAC`() {
        val h1 = hasher.hmac(canonical(tenantId = 1))
        val h2 = hasher.hmac(canonical(tenantId = 2))
        assertFalse(h1.contentEquals(h2))
    }

    @Test
    fun `changing userId changes HMAC`() {
        val h1 = hasher.hmac(canonical(userId = 1))
        val h2 = hasher.hmac(canonical(userId = 99))
        assertFalse(h1.contentEquals(h2))
    }

    @Test
    fun `changing eventType changes HMAC`() {
        val h1 = hasher.hmac(canonical(eventType = "LOGIN_SUCCESS"))
        val h2 = hasher.hmac(canonical(eventType = "LOGIN_FAILED"))
        assertFalse(h1.contentEquals(h2))
    }

    @Test
    fun `changing prevHash changes HMAC`() {
        val prev1 = ByteArray(32) { 0 }
        val prev2 = ByteArray(32) { 1 }
        val h1 = hasher.hmac(canonical(prevHash = prev1))
        val h2 = hasher.hmac(canonical(prevHash = prev2))
        assertFalse(h1.contentEquals(h2))
    }

    @Test
    fun `null tenantId produces dash in canonical string`() {
        val bytes = canonical(tenantId = null)
        val s = bytes.toString(Charsets.UTF_8)
        // format: v1|<id>|<tenantId>|...
        val parts = s.split("|")
        assertEquals("-", parts[2])
    }

    @Test
    fun `null prevHash produces dash in canonical string`() {
        val bytes = canonical(prevHash = null)
        val s = bytes.toString(Charsets.UTF_8)
        assertTrue(s.endsWith("-"), "Last field should be - for null prevHash, got: $s")
    }

    @Test
    fun `different secret keys produce different chain key ids`() {
        val hasher2 = AuditChainHasher("different-secret-key-entirely")
        assertNotEquals(hasher.chainKeyId, hasher2.chainKeyId)
    }

    @Test
    fun `hmac produces 32 bytes`() {
        val h = hasher.hmac(canonical())
        assertEquals(32, h.size, "HMAC-SHA256 must be 32 bytes")
    }
}
