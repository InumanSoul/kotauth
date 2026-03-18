package com.kauth.domain.port

import com.kauth.domain.model.Application

/**
 * Port (outbound) — defines what the domain needs from application/client persistence.
 */
interface ApplicationRepository {
    fun findByTenantId(tenantId: Int): List<Application>

    fun findByClientId(
        tenantId: Int,
        clientId: String,
    ): Application?

    fun findById(id: Int): Application?

    fun existsByClientId(
        tenantId: Int,
        clientId: String,
    ): Boolean

    /** Returns the raw bcrypt hash of the client secret, or null if unset. */
    fun findClientSecretHash(clientPk: Int): String?

    /** Updates (or sets) the client's secret hash. */
    fun setClientSecretHash(
        clientPk: Int,
        secretHash: String,
    )

    fun create(
        tenantId: Int,
        clientId: String,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>,
    ): Application

    /** Updates mutable fields (name, description, accessType, redirectUris). clientId is immutable. */
    fun update(
        appId: Int,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>,
    ): Application

    /** Enables or disables the application. */
    fun setEnabled(
        appId: Int,
        enabled: Boolean,
    )
}
