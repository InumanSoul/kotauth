package com.kauth.domain.port

import com.kauth.domain.model.TenantKey

/**
 * Port — tenant RSA key pair storage.
 * Implemented by [PostgresTenantKeyRepository].
 */
interface TenantKeyRepository {
    /** Returns the active signing key for a tenant, or null if none exists. */
    fun findActiveKey(tenantId: Int): TenantKey?

    /** Returns all enabled keys for a tenant (used by the JWKS endpoint). */
    fun findEnabledKeys(tenantId: Int): List<TenantKey>

    /** Persists a newly generated key pair. */
    fun save(key: TenantKey): TenantKey

    /** Disables a specific key (soft rotation — does not delete). */
    fun disable(
        tenantId: Int,
        keyId: String,
    )
}
