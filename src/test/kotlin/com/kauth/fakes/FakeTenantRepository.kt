package com.kauth.fakes

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.TenantRepository

/**
 * In-memory TenantRepository for unit tests.
 * Thread-safety is not a concern here — tests run single-threaded.
 */
class FakeTenantRepository : TenantRepository {
    private val store = mutableMapOf<Int, Tenant>()
    private var nextId = 1

    fun add(tenant: Tenant): Tenant {
        val t = if (tenant.id == 0) tenant.copy(id = nextId++) else tenant
        store[t.id] = t
        return t
    }

    fun clear() {
        store.clear()
        nextId = 1
    }

    override fun findBySlug(slug: String) = store.values.find { it.slug == slug }

    override fun findById(id: Int) = store[id]

    override fun existsBySlug(slug: String) = store.values.any { it.slug == slug }

    override fun findAll(): List<Tenant> = store.values.toList()

    override fun create(
        slug: String,
        displayName: String,
        issuerUrl: String?,
    ): Tenant {
        val t = Tenant(id = nextId++, slug = slug, displayName = displayName, issuerUrl = issuerUrl)
        store[t.id] = t
        return t
    }

    override fun update(tenant: Tenant): Tenant {
        store[tenant.id] = tenant
        return tenant
    }
}
