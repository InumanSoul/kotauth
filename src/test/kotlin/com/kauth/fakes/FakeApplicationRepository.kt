package com.kauth.fakes

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.port.ApplicationRepository

/**
 * In-memory ApplicationRepository for unit tests.
 * Supports adding pre-built Application fixtures with optional secret hashes.
 */
class FakeApplicationRepository : ApplicationRepository {

    private val store        = mutableMapOf<Int, Application>()
    private val secretHashes = mutableMapOf<Int, String>()      // appPk -> bcrypt-hash
    private var nextId = 1

    fun clear() { store.clear(); secretHashes.clear(); nextId = 1 }

    /** Adds a pre-built Application fixture. If id == 0 a new id is assigned. */
    fun add(app: Application, secretHash: String? = null): Application {
        val a = if (app.id == 0) app.copy(id = nextId++) else app
        store[a.id] = a
        if (secretHash != null) secretHashes[a.id] = secretHash
        return a
    }

    override fun findByTenantId(tenantId: Int) =
        store.values.filter { it.tenantId == tenantId }

    override fun findByClientId(tenantId: Int, clientId: String) =
        store.values.find { it.tenantId == tenantId && it.clientId == clientId }

    override fun findById(id: Int) = store[id]

    override fun existsByClientId(tenantId: Int, clientId: String) =
        store.values.any { it.tenantId == tenantId && it.clientId == clientId }

    override fun findClientSecretHash(clientPk: Int) = secretHashes[clientPk]

    override fun setClientSecretHash(clientPk: Int, secretHash: String) {
        secretHashes[clientPk] = secretHash
    }

    override fun create(
        tenantId    : Int,
        clientId    : String,
        name        : String,
        description : String?,
        accessType  : String,
        redirectUris: List<String>
    ): Application {
        val app = Application(
            id           = nextId++,
            tenantId     = tenantId,
            clientId     = clientId,
            name         = name,
            description  = description,
            accessType   = AccessType.fromValue(accessType),
            enabled      = true,
            redirectUris = redirectUris
        )
        store[app.id] = app
        return app
    }

    override fun update(
        appId       : Int,
        name        : String,
        description : String?,
        accessType  : String,
        redirectUris: List<String>
    ): Application {
        val updated = store[appId]!!.copy(
            name         = name,
            description  = description,
            accessType   = AccessType.fromValue(accessType),
            redirectUris = redirectUris
        )
        store[appId] = updated
        return updated
    }

    override fun setEnabled(appId: Int, enabled: Boolean) {
        store[appId]?.let { store[appId] = it.copy(enabled = enabled) }
    }
}
