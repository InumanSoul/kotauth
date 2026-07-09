package com.kauth.domain.model

import java.time.Instant
import java.util.UUID

data class WebAuthnCredential(
    val id: Long? = null,
    val userId: UserId,
    val tenantId: TenantId,
    val credentialId: String,
    val publicKeyCose: ByteArray,
    val signCounter: Long,
    val aaguid: UUID?,
    val transports: List<String>,
    val name: String,
    val backupEligible: Boolean,
    val backupState: Boolean,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebAuthnCredential) return false
        return id == other.id &&
            userId == other.userId &&
            tenantId == other.tenantId &&
            credentialId == other.credentialId &&
            publicKeyCose.contentEquals(other.publicKeyCose) &&
            signCounter == other.signCounter &&
            aaguid == other.aaguid &&
            transports == other.transports &&
            name == other.name &&
            backupEligible == other.backupEligible &&
            backupState == other.backupState &&
            createdAt == other.createdAt &&
            lastUsedAt == other.lastUsedAt
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + userId.hashCode()
        result = 31 * result + tenantId.hashCode()
        result = 31 * result + credentialId.hashCode()
        result = 31 * result + publicKeyCose.contentHashCode()
        result = 31 * result + signCounter.hashCode()
        result = 31 * result + (aaguid?.hashCode() ?: 0)
        result = 31 * result + transports.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + backupEligible.hashCode()
        result = 31 * result + backupState.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (lastUsedAt?.hashCode() ?: 0)
        return result
    }
}
