package com.kauth.domain.port

import com.kauth.domain.model.Tenant

/**
 * Port (outbound) — defines what the domain needs from tenant persistence.
 */
interface TenantRepository {
    fun findBySlug(slug: String): Tenant?
    fun existsBySlug(slug: String): Boolean
    fun findAll(): List<Tenant>
    fun create(slug: String, displayName: String, issuerUrl: String? = null): Tenant
}
