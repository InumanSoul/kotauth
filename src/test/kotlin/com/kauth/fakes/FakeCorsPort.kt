package com.kauth.fakes

import com.kauth.domain.port.CorsPolicy
import com.kauth.domain.port.CorsPort

class FakeCorsPort : CorsPort {
    private val policies = mutableMapOf<String, CorsPolicy>()
    private val _invalidated = mutableListOf<String>()

    /** Slugs that have been passed to [invalidate] since the last [clearInvalidated] call. */
    val invalidated: List<String> get() = _invalidated.toList()

    fun setPolicy(
        tenantSlug: String,
        allowedOrigins: Set<String>,
        allowCredentials: Boolean = false,
    ) {
        policies[tenantSlug] = CorsPolicy(allowedOrigins, allowCredentials)
    }

    fun removePolicy(tenantSlug: String) {
        policies.remove(tenantSlug)
    }

    fun clearInvalidated() {
        _invalidated.clear()
    }

    override fun policyForTenant(tenantSlug: String): CorsPolicy? = policies[tenantSlug]

    override fun invalidate(tenantSlug: String) {
        _invalidated.add(tenantSlug)
    }
}
