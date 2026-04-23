package com.kauth.domain.port

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserAttribute
import com.kauth.domain.model.UserId

/**
 * Port (outbound) — per-user key/value attribute storage.
 *
 * All operations are tenant-scoped. Attribute deletion cascades from users
 * at the DB level, but [deleteAllForUser] is provided for explicit cleanup.
 */
interface UserAttributeRepository {
    /** Returns all attributes for a user as a flat key→value map. Empty if none. */
    fun findAll(
        userId: UserId,
        tenantId: TenantId,
    ): Map<String, String>

    /** Upserts a single attribute. Creates on conflict, overwrites on match. */
    fun upsert(attribute: UserAttribute)

    /** Deletes a single attribute. Returns true if a row was removed. */
    fun delete(
        userId: UserId,
        tenantId: TenantId,
        key: String,
    ): Boolean

    /** Removes every attribute for a user. Used when the user is deleted. */
    fun deleteAllForUser(
        userId: UserId,
        tenantId: TenantId,
    )
}
