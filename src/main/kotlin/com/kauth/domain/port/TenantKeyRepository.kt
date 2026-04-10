package com.kauth.domain.port

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantKey

/**
 * Port — tenant RSA key pair storage.
 * Implemented by [PostgresTenantKeyRepository].
 */
interface TenantKeyRepository {
    /** Returns the single active signing key for a tenant, or null if none exists. */
    fun findActiveKey(tenantId: TenantId): TenantKey?

    /** Returns all enabled keys for a tenant (used by the JWKS endpoint). */
    fun findEnabledKeys(tenantId: TenantId): List<TenantKey>

    /** Returns all keys for a tenant including retired ones (used by the admin key management page). */
    fun findAllKeys(tenantId: TenantId): List<TenantKey>

    /** Returns a specific key by its keyId, regardless of enabled/active state. */
    fun findByKeyId(
        tenantId: TenantId,
        keyId: String,
    ): TenantKey?

    /** Persists a newly generated key pair. */
    fun save(key: TenantKey): TenantKey

    /** Atomically promotes [newKeyId] to active and demotes [previousKeyId]. */
    fun rotate(
        tenantId: TenantId,
        newKeyId: String,
        previousKeyId: String,
    )

    /** Disables a specific key — removes it from JWKS. */
    fun disable(
        tenantId: TenantId,
        keyId: String,
    )
}
