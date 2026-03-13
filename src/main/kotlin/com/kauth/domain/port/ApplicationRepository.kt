package com.kauth.domain.port

import com.kauth.domain.model.Application

/**
 * Port (outbound) — defines what the domain needs from application/client persistence.
 */
interface ApplicationRepository {
    fun findByTenantId(tenantId: Int): List<Application>
    fun findByClientId(tenantId: Int, clientId: String): Application?
    fun existsByClientId(tenantId: Int, clientId: String): Boolean
    fun create(
        tenantId: Int,
        clientId: String,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>
    ): Application
}
