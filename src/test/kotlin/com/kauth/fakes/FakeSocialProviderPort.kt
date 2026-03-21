package com.kauth.fakes

import com.kauth.domain.model.SocialProvider
import com.kauth.domain.port.SocialProviderPort
import com.kauth.domain.port.SocialUserProfile

/**
 * In-memory SocialProviderPort for unit tests.
 * Returns a configurable profile or throws to simulate provider failures.
 */
class FakeSocialProviderPort(
    override val provider: SocialProvider,
) : SocialProviderPort {
    var profileToReturn: SocialUserProfile? = null
    var shouldFail: Boolean = false
    var authorizationUrl: String = "https://provider.example.com/auth"

    fun clear() {
        profileToReturn = null
        shouldFail = false
        authorizationUrl = "https://provider.example.com/auth"
    }

    override fun exchangeCodeForProfile(
        code: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String,
    ): SocialUserProfile {
        if (shouldFail) throw RuntimeException("Provider exchange failed")
        return profileToReturn ?: throw RuntimeException("No profile configured in fake")
    }

    override fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        scopes: List<String>,
    ): String = "$authorizationUrl?client_id=$clientId&redirect_uri=$redirectUri&state=$state"
}
