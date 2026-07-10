package com.kauth.domain.port

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import java.time.Instant

interface WebAuthnCredentialRepository {
    fun save(credential: WebAuthnCredential): WebAuthnCredential

    fun findById(id: Long): WebAuthnCredential?

    fun findByCredentialId(credentialId: String): WebAuthnCredential?

    fun findByUserId(
        userId: UserId,
        tenantId: TenantId,
    ): List<WebAuthnCredential>

    fun updateCounter(
        id: Long,
        signCounter: Long,
        lastUsedAt: Instant,
    )

    fun rename(
        id: Long,
        userId: UserId,
        newName: String,
    ): Boolean

    fun delete(
        id: Long,
        userId: UserId,
    ): Boolean

    fun deleteAllByUserId(
        userId: UserId,
        tenantId: TenantId,
    ): Int

    fun countEnrolledUsersByTenantId(tenantId: TenantId): Int

    /** Returns the set of user IDs that have at least one credential in [tenantId]. */
    fun findUserIdsWithCredential(tenantId: TenantId): Set<UserId>
}
