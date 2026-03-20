package com.kauth.fakes

import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.port.SocialAccountRepository

/**
 * In-memory SocialAccountRepository for unit tests.
 */
class FakeSocialAccountRepository : SocialAccountRepository {
    private val store = mutableMapOf<Int, SocialAccount>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    fun all(): List<SocialAccount> = store.values.toList()

    override fun findByProviderIdentity(
        tenantId: Int,
        provider: SocialProvider,
        providerUserId: String,
    ): SocialAccount? =
        store.values.find {
            it.tenantId == tenantId && it.provider == provider && it.providerUserId == providerUserId
        }

    override fun findByUserId(userId: Int): List<SocialAccount> =
        store.values.filter { it.userId == userId }

    override fun save(account: SocialAccount): SocialAccount {
        val a = if (account.id == null) account.copy(id = nextId++) else account
        store[a.id!!] = a
        return a
    }

    override fun delete(userId: Int, provider: SocialProvider) {
        store.entries.removeIf { it.value.userId == userId && it.value.provider == provider }
    }
}
