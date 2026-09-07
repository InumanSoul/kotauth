package com.kauth.fakes

import com.kauth.domain.model.ProviderKey
import com.kauth.domain.port.OidcRequestBinding
import com.kauth.domain.port.SocialProviderPort
import com.kauth.domain.port.SocialUserProfile

/**
 * In-memory SocialProviderPort for unit tests.
 * Returns a configurable profile or throws to simulate provider failures.
 *
 * It records the [OidcRequestBinding] each call was handed, so a test can assert that the nonce
 * and PKCE verifier the redirect began with are the ones the callback replays.
 */
class FakeSocialProviderPort(
    override val provider: ProviderKey,
) : SocialProviderPort {
    var profileToReturn: SocialUserProfile? = null
    var shouldFail: Boolean = false
    var authorizationUrl: String = "https://provider.example.com/auth"

    /** The binding handed to [buildAuthorizationUrl], and the one handed to [exchangeCodeForProfile]. */
    var bindingAtRedirect: OidcRequestBinding? = null
    var bindingAtExchange: OidcRequestBinding? = null

    fun clear() {
        profileToReturn = null
        shouldFail = false
        authorizationUrl = "https://provider.example.com/auth"
        bindingAtRedirect = null
        bindingAtExchange = null
    }

    override fun exchangeCodeForProfile(
        code: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String,
        binding: OidcRequestBinding?,
    ): SocialUserProfile {
        bindingAtExchange = binding
        if (shouldFail) throw RuntimeException("Provider exchange failed")
        return profileToReturn ?: throw RuntimeException("No profile configured in fake")
    }

    override fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        scopes: List<String>,
        binding: OidcRequestBinding?,
    ): String {
        bindingAtRedirect = binding
        return "$authorizationUrl?client_id=$clientId&redirect_uri=$redirectUri&state=$state"
    }
}
