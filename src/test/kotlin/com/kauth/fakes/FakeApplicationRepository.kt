package com.kauth.fakes

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ApplicationRepository

/**
 * In-memory ApplicationRepository for unit tests.
 * Supports adding pre-built Application fixtures with optional secret hashes.
 */
class FakeApplicationRepository : ApplicationRepository {
    private val store = mutableMapOf<Int, Application>()
    private val secretHashes = mutableMapOf<Int, String>() // appPk -> bcrypt-hash
    private var nextId = 1

    fun clear() {
        store.clear()
        secretHashes.clear()
        nextId = 1
    }

    /** Adds a pre-built Application fixture. If id == 0 a new id is assigned. */
    fun add(
        app: Application,
        secretHash: String? = null,
    ): Application {
        val a = if (app.id.value == 0) app.copy(id = ApplicationId(nextId++)) else app
        store[a.id.value] = a
        if (secretHash != null) secretHashes[a.id.value] = secretHash
        return a
    }

    override fun findByTenantId(tenantId: TenantId) = store.values.filter { it.tenantId == tenantId }

    override fun findByClientId(
        tenantId: TenantId,
        clientId: String,
    ) = store.values.find { it.tenantId == tenantId && it.clientId == clientId }

    override fun findById(id: ApplicationId) = store[id.value]

    override fun existsByClientId(
        tenantId: TenantId,
        clientId: String,
    ) = store.values.any { it.tenantId == tenantId && it.clientId == clientId }

    override fun findClientSecretHash(clientPk: ApplicationId) = secretHashes[clientPk.value]

    override fun setClientSecretHash(
        clientPk: ApplicationId,
        secretHash: String,
    ) {
        secretHashes[clientPk.value] = secretHash
    }

    override fun create(
        tenantId: TenantId,
        clientId: String,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>,
    ): Application {
        val app =
            Application(
                id = ApplicationId(nextId++),
                tenantId = tenantId,
                clientId = clientId,
                name = name,
                description = description,
                accessType = AccessType.fromValue(accessType),
                enabled = true,
                redirectUris = redirectUris,
            )
        store[app.id.value] = app
        return app
    }

    override fun update(
        appId: ApplicationId,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>,
    ): Application {
        val updated =
            store[appId.value]!!.copy(
                name = name,
                description = description,
                accessType = AccessType.fromValue(accessType),
                redirectUris = redirectUris,
            )
        store[appId.value] = updated
        return updated
    }

    override fun setEnabled(
        appId: ApplicationId,
        enabled: Boolean,
    ) {
        store[appId.value]?.let { store[appId.value] = it.copy(enabled = enabled) }
    }
}
