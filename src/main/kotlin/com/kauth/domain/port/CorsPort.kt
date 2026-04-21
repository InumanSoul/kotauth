package com.kauth.domain.port

data class CorsPolicy(
    val allowedOrigins: Set<String>,
    val allowCredentials: Boolean,
)

interface CorsPort {
    /** Returns the CORS policy for a tenant, or null if the tenant does not exist. */
    fun policyForTenant(tenantSlug: String): CorsPolicy?

    /** Invalidates any cached policy for [tenantSlug]. No-op for implementations without caching. */
    fun invalidate(tenantSlug: String) {}
}
