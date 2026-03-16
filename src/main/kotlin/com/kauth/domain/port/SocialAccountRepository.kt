package com.kauth.domain.port

import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.SocialProvider

/**
 * Port — persistence contract for social account links.
 * Implemented by PostgresSocialAccountRepository.
 */
interface SocialAccountRepository {

    /**
     * Finds a social account by provider identity.
     * Used during callback to check if the provider user is already linked to a local account.
     */
    fun findByProviderIdentity(
        tenantId       : Int,
        provider       : SocialProvider,
        providerUserId : String
    ): SocialAccount?

    /** Returns all social accounts linked to a local user. */
    fun findByUserId(userId: Int): List<SocialAccount>

    /** Persists a new social account link and returns it with the generated id. */
    fun save(account: SocialAccount): SocialAccount

    /** Removes a social account link (user unlinks a provider). */
    fun delete(userId: Int, provider: SocialProvider)
}
