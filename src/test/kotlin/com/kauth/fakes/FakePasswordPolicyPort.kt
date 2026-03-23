package com.kauth.fakes

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.PasswordPolicyPort

/**
 * In-memory PasswordPolicyPort for unit tests.
 * Configurable validation result and history tracking.
 */
class FakePasswordPolicyPort : PasswordPolicyPort {
    var validationError: String? = null
    private val history = mutableListOf<Triple<UserId, TenantId, String>>() // userId, tenantId, hash
    private val blacklist = mutableSetOf<String>()

    fun clear() {
        validationError = null
        history.clear()
        blacklist.clear()
    }

    fun addToBlacklist(password: String) {
        blacklist.add(password)
    }

    override fun validate(
        rawPassword: String,
        tenant: Tenant,
        userId: UserId?,
    ): String? = validationError

    override fun recordPasswordHistory(
        userId: UserId,
        tenantId: TenantId,
        passwordHash: String,
    ) {
        history.add(Triple(userId, tenantId, passwordHash))
    }

    override fun isInHistory(
        userId: UserId,
        tenantId: TenantId,
        rawPassword: String,
        historyCount: Int,
    ): Boolean =
        history
            .filter { it.first == userId && it.second == tenantId }
            .takeLast(historyCount)
            .any { it.third == "hashed:$rawPassword" }

    override fun isBlacklisted(
        rawPassword: String,
        tenantId: TenantId,
    ): Boolean = rawPassword in blacklist
}
