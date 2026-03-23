package com.kauth.domain.port

import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.TenantId

/**
 * Port (outbound) — defines what the domain needs from application/client persistence.
 */
interface ApplicationRepository {
    fun findByTenantId(tenantId: TenantId): List<Application>

    fun findByClientId(
        tenantId: TenantId,
        clientId: String,
    ): Application?

    fun findById(id: ApplicationId): Application?

    fun existsByClientId(
        tenantId: TenantId,
        clientId: String,
    ): Boolean

    /** Returns the raw bcrypt hash of the client secret, or null if unset. */
    fun findClientSecretHash(clientPk: ApplicationId): String?

    /** Updates (or sets) the client's secret hash. */
    fun setClientSecretHash(
        clientPk: ApplicationId,
        secretHash: String,
    )

    fun create(
        tenantId: TenantId,
        clientId: String,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>,
    ): Application

    /** Updates mutable fields (name, description, accessType, redirectUris). clientId is immutable. */
    fun update(
        appId: ApplicationId,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>,
    ): Application

    /** Enables or disables the application. */
    fun setEnabled(
        appId: ApplicationId,
        enabled: Boolean,
    )
}
