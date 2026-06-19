package com.kauth.infrastructure

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Computes the HMAC-SHA256 audit chain for append-only tamper detection.
 *
 * The MAC key is derived from KAUTH_SECRET_KEY via a domain-separated SHA-256:
 *   auditMacKey = sha256("$rawSecretKey|kauth/audit-log/v1")
 *
 * Each row carries:
 *   - prev_hash: row_hash of the immediately preceding row for this tenant (null for the first row)
 *   - row_hash: HMAC-SHA256 over the canonical row string
 *   - chain_key_id: first 8 hex chars of sha256(auditMacKey), identifies which key signed the chain
 */
class AuditChainHasher(
    rawSecretKey: String,
) {
    private val auditMacKey: ByteArray = sha256("$rawSecretKey|kauth/audit-log/v1".toByteArray(StandardCharsets.UTF_8))
    val chainKeyId: String = sha256(auditMacKey).toHex().take(8)

    fun canonicalize(
        id: Int,
        tenantId: Int?,
        userId: Int?,
        clientId: Int?,
        eventType: String,
        ipAddress: String?,
        userAgent: String?,
        createdAt: OffsetDateTime,
        detailsJson: String?,
        prevHash: ByteArray?,
    ): ByteArray {
        fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")
        val parts =
            listOf(
                "v1",
                enc(id.toString()),
                tenantId?.let { enc(it.toString()) } ?: "-",
                userId?.let { enc(it.toString()) } ?: "-",
                clientId?.let { enc(it.toString()) } ?: "-",
                enc(eventType),
                ipAddress?.let { enc(it) } ?: "-",
                userAgent?.let { enc(it) } ?: "-",
                enc(createdAt.toString()),
                detailsJson?.let { enc(it) } ?: "-",
                prevHash?.toHex() ?: "-",
            )
        return parts.joinToString("|").toByteArray(StandardCharsets.UTF_8)
    }

    fun hmac(canonical: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(auditMacKey, "HmacSHA256"))
        return mac.doFinal(canonical)
    }

    private companion object {
        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
