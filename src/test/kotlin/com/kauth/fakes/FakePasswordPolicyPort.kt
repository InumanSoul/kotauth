package com.kauth.fakes

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.PasswordPolicyPort

/**
 * In-memory PasswordPolicyPort for unit tests.
 * Configurable validation result and history tracking.
 */
class FakePasswordPolicyPort : PasswordPolicyPort {
    var validationError: String? = null
    private val history = mutableListOf<Triple<Int, Int, String>>() // userId, tenantId, hash
    private val blacklist = mutableSetOf<String>()

    fun clear() {
        validationError = null
        history.clear()
        blacklist.clear()
    }

    fun addToBlacklist(password: String) {
        blacklist.add(password)
    }

    override fun validate(rawPassword: String, tenant: Tenant, userId: Int?): String? =
        validationError

    override fun recordPasswordHistory(userId: Int, tenantId: Int, passwordHash: String) {
        history.add(Triple(userId, tenantId, passwordHash))
    }

    override fun isInHistory(userId: Int, tenantId: Int, rawPassword: String, historyCount: Int): Boolean =
        history.filter { it.first == userId && it.second == tenantId }
            .takeLast(historyCount)
            .any { it.third == "hashed:$rawPassword" }

    override fun isBlacklisted(rawPassword: String, tenantId: Int): Boolean =
        rawPassword in blacklist
}
