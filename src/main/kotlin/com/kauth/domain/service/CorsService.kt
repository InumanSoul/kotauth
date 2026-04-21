package com.kauth.domain.service

import com.kauth.domain.model.CorsDecision
import com.kauth.domain.port.CorsPort

class CorsService(
    private val corsPort: CorsPort,
) {
    fun decide(
        tenantSlug: String,
        origin: String,
        endpointPath: String,
    ): CorsDecision {
        if (isPublicEndpoint(endpointPath)) return CorsDecision.Public

        val policy = corsPort.policyForTenant(tenantSlug) ?: return CorsDecision.Denied
        if (origin !in policy.allowedOrigins) return CorsDecision.Denied

        return CorsDecision.Allowed(origin, policy.allowCredentials)
    }

    private fun isPublicEndpoint(path: String): Boolean =
        path.endsWith("/.well-known/openid-configuration") ||
            path.endsWith("/protocol/openid-connect/certs")
}
