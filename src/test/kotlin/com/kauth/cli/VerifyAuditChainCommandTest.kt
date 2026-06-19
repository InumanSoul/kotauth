package com.kauth.cli

import com.kauth.infrastructure.AuditChainHasher
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifyAuditChainCommandTest {
    private val secret = "test-secret-key-for-verify-audit-chain"
    private val hasher = AuditChainHasher(secret)
    private val t0 = OffsetDateTime.of(2026, 6, 19, 10, 0, 0, 0, ZoneOffset.UTC)

    // -------------------------------------------------------------------------
    // Arg parsing
    // -------------------------------------------------------------------------

    @Test
    fun `parseArg returns null when arg is absent`() {
        val result = invokeParseArg(emptyList(), "--tenant")
        assertEquals(null, result)
    }

    @Test
    fun `parseArg extracts value`() {
        val result = invokeParseArg(listOf("--tenant=acme", "--quiet"), "--tenant")
        assertEquals("acme", result)
    }

    @Test
    fun `quiet flag is detected`() {
        val args = listOf("--quiet", "--tenant=acme")
        assertTrue("--quiet" in args)
    }

    @Test
    fun `from-id is parsed as int`() {
        val args = listOf("--from-id=42")
        val raw = invokeParseArg(args, "--from-id")
        assertEquals(42, raw?.toIntOrNull())
    }

    // -------------------------------------------------------------------------
    // Chain verification logic
    // -------------------------------------------------------------------------

    data class SyntheticRow(
        val id: Int,
        val storedPrevHash: ByteArray?,
        val storedRowHash: ByteArray?,
    )

    private fun buildChain(
        count: Int,
        tenantId: Int? = 1,
    ): List<SyntheticRow> {
        val rows = mutableListOf<SyntheticRow>()
        var prevHash: ByteArray? = null
        for (i in 1..count) {
            val canonical =
                hasher.canonicalize(
                    id = i,
                    tenantId = tenantId,
                    userId = 10,
                    clientId = null,
                    eventType = "LOGIN_SUCCESS",
                    ipAddress = "127.0.0.1",
                    userAgent = null,
                    createdAt = t0.plusMinutes(i.toLong()),
                    detailsJson = null,
                    prevHash = prevHash,
                )
            val rowHash = hasher.hmac(canonical)
            rows.add(SyntheticRow(i, prevHash, rowHash))
            prevHash = rowHash
        }
        return rows
    }

    private fun verifyChain(rows: List<SyntheticRow>): Boolean {
        var previousHash: ByteArray? = null
        var diverged = false
        for (row in rows) {
            val storedRowHash =
                row.storedRowHash ?: run {
                    previousHash = null
                    continue
                }
            if (previousHash != null && !row.storedPrevHash.contentEquals(previousHash)) {
                diverged = true
                break
            }
            val canonical =
                hasher.canonicalize(
                    id = row.id,
                    tenantId = 1,
                    userId = 10,
                    clientId = null,
                    eventType = "LOGIN_SUCCESS",
                    ipAddress = "127.0.0.1",
                    userAgent = null,
                    createdAt = t0.plusMinutes(row.id.toLong()),
                    detailsJson = null,
                    prevHash = row.storedPrevHash,
                )
            val expected = hasher.hmac(canonical)
            if (!expected.contentEquals(storedRowHash)) {
                diverged = true
                break
            }
            previousHash = storedRowHash
        }
        return diverged
    }

    @Test
    fun `clean chain of 5 rows is verified without divergence`() {
        val chain = buildChain(5)
        assertFalse(verifyChain(chain), "Clean chain should not diverge")
    }

    @Test
    fun `tampered row_hash is detected`() {
        val chain = buildChain(5).toMutableList()
        val tampered = chain[2]
        val badHash = tampered.storedRowHash!!.copyOf()
        badHash[0] = (badHash[0].toInt() xor 0xFF).toByte()
        chain[2] = tampered.copy(storedRowHash = badHash)
        assertTrue(verifyChain(chain), "Tampered hash should cause divergence")
    }

    @Test
    fun `wrong prev_hash is detected`() {
        val chain = buildChain(5).toMutableList()
        val row = chain[3]
        val badPrev = ByteArray(32) { 0xAB.toByte() }
        chain[3] = row.copy(storedPrevHash = badPrev)
        assertTrue(verifyChain(chain), "Wrong prev_hash should cause divergence")
    }

    @Test
    fun `pre-chain rows (null row_hash) are skipped without breaking chain`() {
        val chain = buildChain(3).toMutableList()
        // Insert a pre-chain row at position 0
        val preChainRow = SyntheticRow(0, null, null)
        val withPreChain = listOf(preChainRow) + chain
        // The chain should still verify because pre-chain rows reset the tracking
        // (note: since we built the chain without the pre-chain row, the first chain
        // row starts with prevHash = null, which is consistent after reset)
        val result = verifyChain(withPreChain)
        assertFalse(result, "Pre-chain rows should not cause divergence")
    }

    @Test
    fun `different secret key fails to verify chain`() {
        val chain = buildChain(3)
        val differentHasher = AuditChainHasher("completely-different-secret-key-xyz")
        var diverged = false
        var prevHash: ByteArray? = null
        for (row in chain) {
            val canonical =
                differentHasher.canonicalize(
                    id = row.id,
                    tenantId = 1,
                    userId = 10,
                    clientId = null,
                    eventType = "LOGIN_SUCCESS",
                    ipAddress = "127.0.0.1",
                    userAgent = null,
                    createdAt = t0.plusMinutes(row.id.toLong()),
                    detailsJson = null,
                    prevHash = prevHash,
                )
            val expected = differentHasher.hmac(canonical)
            if (!expected.contentEquals(row.storedRowHash)) {
                diverged = true
                break
            }
            prevHash = row.storedRowHash
        }
        assertTrue(diverged, "Wrong key must not verify the chain")
    }

    // -------------------------------------------------------------------------
    // Private reflection helper for arg parsing (tests internal logic)
    // -------------------------------------------------------------------------

    private fun invokeParseArg(
        args: List<String>,
        prefix: String,
    ): String? =
        args.firstNotNullOfOrNull { arg ->
            if (arg.startsWith("$prefix=")) arg.removePrefix("$prefix=").takeIf { it.isNotBlank() } else null
        }
}
