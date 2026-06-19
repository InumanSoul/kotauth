package com.kauth.infrastructure

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 audit chain — key derived from KAUTH_SECRET_KEY with domain separation. */
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
        // RFC 3986 percent-encoding (spaces → %20, not application/x-www-form +) so external
        // verifiers built against the spec produce identical canonical bytes.
        fun enc(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
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
