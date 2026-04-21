package com.kauth.adapter.web.plugin

import com.kauth.domain.port.CorsPolicy
import com.kauth.domain.port.CorsPort
import java.util.concurrent.ConcurrentHashMap

class CorsOriginCache(
    private val delegate: CorsPort,
    private val ttlMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : CorsPort {
    private data class Entry(
        val policy: CorsPolicy?,
        val loadedAt: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun policyForTenant(tenantSlug: String): CorsPolicy? {
        val now = clock()
        val cached = cache[tenantSlug]
        if (cached != null && now - cached.loadedAt < ttlMillis) {
            return cached.policy
        }
        val fresh = delegate.policyForTenant(tenantSlug)
        cache[tenantSlug] = Entry(fresh, now)
        return fresh
    }

    override fun invalidate(tenantSlug: String) {
        cache.remove(tenantSlug)
    }

    fun evictAll() {
        cache.clear()
    }
}
