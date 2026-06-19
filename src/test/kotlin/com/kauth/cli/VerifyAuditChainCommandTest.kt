package com.kauth.cli

import com.kauth.infrastructure.AuditChainHasher
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifyAuditChainCommandTest {
    private val secret = "test-secret-key-for-verify-audit-chain"
    private val hasher = AuditChainHasher(secret)
    private val t0 = OffsetDateTime.of(2026, 6, 19, 10, 0, 0, 0, ZoneOffset.UTC)

    data class SyntheticRow(
        val id: Int,
        val tenantId: Int?,
        val userId: Int?,
        val clientId: Int?,
        val eventType: String,
        val ipAddress: String?,
        val userAgent: String?,
        val createdAt: OffsetDateTime,
        val detailsJson: String?,
        val storedPrevHash: ByteArray?,
        val storedRowHash: ByteArray?,
    )

    private fun build(
        count: Int,
        tenantId: Int? = 1,
        startId: Int = 1,
    ): List<SyntheticRow> {
        val rows = mutableListOf<SyntheticRow>()
        var prevHash: ByteArray? = null
        for (offset in 0 until count) {
            val id = startId + offset
            val createdAt = t0.plusMinutes(id.toLong())
            val canonical =
                hasher.canonicalize(
                    id = id,
                    tenantId = tenantId,
                    userId = 10,
                    clientId = null,
                    eventType = "LOGIN_SUCCESS",
                    ipAddress = "127.0.0.1",
                    userAgent = null,
                    createdAt = createdAt,
                    detailsJson = null,
                    prevHash = prevHash,
                )
            val rowHash = hasher.hmac(canonical)
            rows.add(
                SyntheticRow(
                    id = id,
                    tenantId = tenantId,
                    userId = 10,
                    clientId = null,
                    eventType = "LOGIN_SUCCESS",
                    ipAddress = "127.0.0.1",
                    userAgent = null,
                    createdAt = createdAt,
                    detailsJson = null,
                    storedPrevHash = prevHash,
                    storedRowHash = rowHash,
                ),
            )
            prevHash = rowHash
        }
        return rows
    }

    /**
     * Mirrors the per-row check in [VerifyAuditChainCommand.verifyTenant]: returns true if any
     * row diverges from the recomputed HMAC over its current field values.
     */
    private fun verify(rows: List<SyntheticRow>): Boolean {
        var previousHash: ByteArray? = null
        for (row in rows) {
            val storedRowHash =
                row.storedRowHash ?: run {
                    previousHash = null
                    return@run null
                } ?: continue
            if (previousHash != null && !row.storedPrevHash.contentEquals(previousHash)) return true
            val canonical =
                hasher.canonicalize(
                    id = row.id,
                    tenantId = row.tenantId,
                    userId = row.userId,
                    clientId = row.clientId,
                    eventType = row.eventType,
                    ipAddress = row.ipAddress,
                    userAgent = row.userAgent,
                    createdAt = row.createdAt,
                    detailsJson = row.detailsJson,
                    prevHash = row.storedPrevHash,
                )
            val expected = hasher.hmac(canonical)
            if (!expected.contentEquals(storedRowHash)) return true
            previousHash = storedRowHash
        }
        return false
    }

    @Test
    fun `clean chain of 5 rows verifies cleanly`() {
        assertFalse(verify(build(5)))
    }

    @Test
    fun `mutating eventType without resigning is detected (primary tamper scenario)`() {
        val chain = build(5).toMutableList()
        chain[2] = chain[2].copy(eventType = "ADMIN_USER_DELETED")
        assertTrue(
            verify(chain),
            "Editing a field while leaving row_hash intact must be detected — this is the threat the chain defends against.",
        )
    }

    @Test
    fun `mutating ipAddress without resigning is detected`() {
        val chain = build(3).toMutableList()
        chain[1] = chain[1].copy(ipAddress = "10.0.0.99")
        assertTrue(verify(chain))
    }

    @Test
    fun `mutating userAgent without resigning is detected`() {
        val chain = build(3).toMutableList()
        chain[1] = chain[1].copy(userAgent = "MaliciousBot/1.0")
        assertTrue(verify(chain))
    }

    @Test
    fun `directly tampered row_hash is detected`() {
        val chain = build(5).toMutableList()
        val bad = chain[2].storedRowHash!!.copyOf()
        bad[0] = (bad[0].toInt() xor 0xFF).toByte()
        chain[2] = chain[2].copy(storedRowHash = bad)
        assertTrue(verify(chain))
    }

    @Test
    fun `forged prev_hash is detected`() {
        val chain = build(5).toMutableList()
        chain[3] = chain[3].copy(storedPrevHash = ByteArray(32) { 0xAB.toByte() })
        assertTrue(verify(chain))
    }

    @Test
    fun `replayed row spliced between two valid rows is detected`() {
        val chain = build(4).toMutableList()
        // Replay row #1 between rows #2 and #3 — id collision aside, the next row's
        // prev_hash refers to the original row #2 not the replayed copy.
        chain.add(2, chain[0])
        assertTrue(
            verify(chain),
            "Splicing a copy of an earlier row between two existing rows must break the chain at the next row.",
        )
    }

    @Test
    fun `tampering with tenant A does not affect tenant B chain verdict`() {
        val a = build(3, tenantId = 1).toMutableList()
        val b = build(3, tenantId = 2)
        a[1] = a[1].copy(eventType = "ADMIN_USER_DELETED")
        assertTrue(verify(a), "Tenant A is tampered; verify must report diverged")
        assertFalse(verify(b), "Tenant B chain is independent and must verify cleanly")
    }

    @Test
    fun `pre-chain rows with null row_hash do not break verification`() {
        val pre = SyntheticRow(0, 1, null, null, "LEGACY", null, null, t0, null, null, null)
        val rows = listOf(pre) + build(3)
        assertFalse(verify(rows))
    }
}
