package com.kauth.domain.port

import com.kauth.domain.model.ApiKey
import java.time.Instant

/**
 * Port (outbound) — API key persistence.
 * All queries are scoped by tenantId to enforce multi-tenant isolation.
 */
interface ApiKeyRepository {
    /** Persists a new API key. [apiKey.id] must be null. Returns the saved entity with id set. */
    fun save(apiKey: ApiKey): ApiKey

    /** Looks up a key by its SHA-256 hash. Used on every authenticated request. */
    fun findByHash(hash: String): ApiKey?

    /** Returns all keys for a tenant ordered by createdAt DESC. Includes disabled keys. */
    fun findByTenantId(tenantId: Int): List<ApiKey>

    /** Finds a specific key by id, scoped to tenant. */
    fun findById(
        id: Int,
        tenantId: Int,
    ): ApiKey?

    /** Soft-revokes a key (sets enabled = false). */
    fun revoke(
        id: Int,
        tenantId: Int,
    )

    /** Updates last_used_at. Best-effort — called after successful auth, outside main transaction. */
    fun touchLastUsed(
        id: Int,
        at: Instant,
    )

    /** Hard-deletes a key. Only used for cleanup; prefer revoke for audit trail. */
    fun delete(
        id: Int,
        tenantId: Int,
    )
}
