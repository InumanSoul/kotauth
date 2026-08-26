package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.Session
import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.OidcRequestBinding
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.SocialAccountRepository
import com.kauth.domain.port.SocialProviderResolver
import com.kauth.domain.port.SocialUserProfile
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TokenPort
import com.kauth.domain.port.UserRepository
import com.kauth.domain.util.SecureTokens
import com.kauth.domain.util.sha256Hex
import java.time.Instant

/**
 * Domain service for social (OAuth2) login.
 *
 * Flow:
 *   1. Route layer calls [buildRedirectUrl] -> user is sent to provider (Google/GitHub)
 *   2. Provider redirects back with ?code=...&state=...
 *   3. Route layer calls [handleCallback] with the code + verified tenant/provider
 *   4a. If an existing KotAuth account is found (via social link or email match) -> issue tokens
 *   4b. If no existing account -> return NeedsRegistration; route redirects to completion page
 *   5. User completes registration; route calls [completeSocialRegistration] -> issue tokens
 *
 * Account resolution in handleCallback (existing users only):
 *   a) Existing social_account row matches (tenant, provider, providerUserId) -> reuse user.
 *   b) Local user with same email exists in same tenant -> auto-link + reuse user.
 *   c) No match -> NeedsRegistration (NO silent user creation).
 *
 * New user creation is ONLY done in [completeSocialRegistration], after the user has
 * confirmed their chosen username on the registration completion page. This ensures:
 *   - Tenant registrationEnabled policy is respected.
 *   - Users know their username before it is set.
 *   - Existing users are never modified.
 */
class SocialLoginService(
    private val identityProviderRepository: IdentityProviderRepository,
    private val socialAccountRepository: SocialAccountRepository,
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val sessionRepository: SessionRepository,
    private val tokenPort: TokenPort,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort,
    private val providerResolver: SocialProviderResolver,
    private val applicationRepository: ApplicationRepository? = null,
    private val roleRepository: RoleRepository? = null,
) {
    /**
     * Builds the provider authorization URL that the browser should be redirected to.
     *
     * [binding] carries the nonce and PKCE verifier the caller generated for this request and
     * signed into its state; an OIDC provider sends the nonce and the derived challenge with the
     * authorization request, and the compiled-in OAuth2 adapters ignore it.
     */
    fun buildRedirectUrl(
        tenantSlug: String,
        provider: ProviderKey,
        state: String,
        baseUrl: String,
        binding: OidcRequestBinding? = null,
    ): SocialLoginResult<String> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return SocialLoginResult.Failure(SocialLoginError.TenantNotFound)

        val idp = identityProviderRepository.findByTenantAndProvider(tenant.id, provider)
        if (idp == null || !idp.enabled) {
            return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)
        }

        val adapter =
            providerResolver.resolve(tenant.id, provider)
                ?: return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)

        // An OIDC adapter resolves its endpoints here, so this call reaches the network and can
        // fail on a misconfigured issuer — an operator error, not a 500.
        val url =
            try {
                adapter.buildAuthorizationUrl(
                    clientId = idp.clientId,
                    redirectUri = callbackUri(baseUrl, tenantSlug, provider),
                    state = state,
                    scopes = emptyList(),
                    binding = binding,
                )
            } catch (e: Exception) {
                return SocialLoginResult.Failure(
                    SocialLoginError.ProviderError("Failed to build the authorization URL: ${e.message}"),
                )
            }
        return SocialLoginResult.Success(url)
    }

    /**
     * Processes the OAuth2 callback. The route layer must verify the state signature
     * and extract tenantSlug + provider before calling this.
     *
     * Returns:
     *   Success           — existing user found and logged in; route issues tokens / auth code.
     *   NeedsRegistration — no existing account; route redirects to registration completion page.
     *   Failure           — provider error, tenant not found, user disabled, etc.
     */
    fun handleCallback(
        tenantSlug: String,
        provider: ProviderKey,
        code: String,
        baseUrl: String,
        ipAddress: String? = null,
        userAgent: String? = null,
        binding: OidcRequestBinding? = null,
    ): SocialLoginResult<SocialLoginSuccess> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return SocialLoginResult.Failure(SocialLoginError.TenantNotFound)

        val idp = identityProviderRepository.findByTenantAndProvider(tenant.id, provider)
        if (idp == null || !idp.enabled) {
            return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)
        }

        val adapter =
            providerResolver.resolve(tenant.id, provider)
                ?: return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)

        val profile =
            try {
                adapter.exchangeCodeForProfile(
                    code = code,
                    redirectUri = callbackUri(baseUrl, tenantSlug, provider),
                    clientId = idp.clientId,
                    clientSecret = idp.clientSecret,
                    binding = binding,
                )
            } catch (e: Exception) {
                return SocialLoginResult.Failure(
                    SocialLoginError.ProviderError("Failed to exchange authorization code: ${e.message}"),
                )
            }

        if (profile.email.isNullOrBlank()) {
            return SocialLoginResult.Failure(SocialLoginError.EmailNotProvided)
        }

        when (val match = resolveExistingUser(tenant.id, provider, profile)) {
            is ExistingUserMatch.Linked -> return issueTokens(match.user, tenant, provider, false, ipAddress, userAgent)
            is ExistingUserMatch.EmailCollisionUnverified ->
                return SocialLoginResult.Failure(SocialLoginError.LinkRequiresEmailVerification)
            ExistingUserMatch.None ->
                return SocialLoginResult.NeedsRegistration(
                    SocialLoginNeedsRegistration(
                        provider = provider,
                        providerUserId = profile.providerUserId,
                        email = profile.email,
                        name = profile.name,
                        avatarUrl = profile.avatarUrl,
                        emailVerified = profile.emailVerified,
                    ),
                )
        }
    }

    /**
     * Completes a social login registration after the user has chosen their username
     * on the registration completion page.
     *
     * Guards:
     *   - tenant.registrationEnabled must be true
     *   - username must be unique, 3–50 chars, alphanumeric + underscore
     *   - race condition: if a link or email match appeared since the callback, reuse that user
     */
    fun completeSocialRegistration(
        tenantSlug: String,
        provider: ProviderKey,
        providerUserId: String,
        email: String,
        providerName: String?,
        avatarUrl: String?,
        emailVerified: Boolean,
        chosenUsername: String,
        ipAddress: String? = null,
        userAgent: String? = null,
        /**
         * The `client_id` of the OAuth client this social registration
         * originated from. When set, that client's default roles are granted
         * to the new user. Mirrors [AuthService.register].
         */
        originatingClientId: String? = null,
    ): SocialLoginResult<SocialLoginSuccess> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return SocialLoginResult.Failure(SocialLoginError.TenantNotFound)

        if (!tenant.registrationEnabled) {
            return SocialLoginResult.Failure(SocialLoginError.RegistrationDisabled)
        }

        val username = chosenUsername.trim()
        if (username.length < 3 || username.length > 50) {
            return SocialLoginResult.Failure(
                SocialLoginError.InvalidUsername("Username must be between 3 and 50 characters."),
            )
        }
        if (!username.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            return SocialLoginResult.Failure(
                SocialLoginError.InvalidUsername("Username may only contain letters, numbers, and underscores."),
            )
        }

        // Race-condition guards: another request may have linked or created this user
        // between the callback and this completion call.
        val existingLink = socialAccountRepository.findByProviderIdentity(tenant.id, provider, providerUserId)
        if (existingLink != null) {
            userRepository.findById(existingLink.userId, tenant.id)?.let { user ->
                return issueTokens(user, tenant, provider, isNewUser = false, ipAddress, userAgent)
            }
        }

        val normalizedEmail = email.trim().lowercase()
        val existingByEmail = userRepository.findByEmail(tenant.id, normalizedEmail)
        if (existingByEmail != null) {
            if (!emailVerified) {
                return SocialLoginResult.Failure(SocialLoginError.LinkRequiresEmailVerification)
            }
            socialAccountRepository.save(
                SocialAccount(
                    userId = existingByEmail.id!!,
                    tenantId = tenant.id,
                    provider = provider,
                    providerUserId = providerUserId,
                    providerEmail = email,
                    providerName = providerName,
                    avatarUrl = avatarUrl,
                ),
            )
            return issueTokens(existingByEmail, tenant, provider, isNewUser = false, ipAddress, userAgent)
        }

        if (userRepository.existsByUsername(tenant.id, username)) {
            return SocialLoginResult.Failure(SocialLoginError.UsernameConflict)
        }

        // Create the new user — social users get an unusable password hash so they cannot
        // log in via password until they explicitly set one through the self-service portal.
        val newUser =
            userRepository.save(
                User(
                    tenantId = tenant.id,
                    username = username,
                    email = normalizedEmail,
                    fullName = providerName?.trim()?.ifBlank { null } ?: username,
                    passwordHash = passwordHasher.hash(generateRandomPassword()),
                    emailVerified = emailVerified,
                ),
            )
        socialAccountRepository.save(
            SocialAccount(
                userId = newUser.id!!,
                tenantId = tenant.id,
                provider = provider,
                providerUserId = providerUserId,
                providerEmail = email,
                providerName = providerName,
                avatarUrl = avatarUrl,
            ),
        )

        grantClientDefaultRoles(tenant.id, newUser.id, originatingClientId)

        return issueTokens(newUser, tenant, provider, isNewUser = true, ipAddress, userAgent)
    }

    private fun grantClientDefaultRoles(
        tenantId: TenantId,
        userId: com.kauth.domain.model.UserId,
        originatingClientId: String?,
    ) = applyClientDefaultRolesGrant(
        tenantId,
        userId,
        originatingClientId,
        applicationRepository,
        roleRepository,
    )

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private sealed class ExistingUserMatch {
        data class Linked(
            val user: User,
        ) : ExistingUserMatch()

        object EmailCollisionUnverified : ExistingUserMatch()

        object None : ExistingUserMatch()
    }

    // Account take-over guard: auto-linking by email REQUIRES the provider has verified
    // the email. Otherwise an attacker who controls an unverified mailbox at the IdP
    // could claim any KotAuth account that shares that address.
    private fun resolveExistingUser(
        tenantId: TenantId,
        provider: ProviderKey,
        profile: SocialUserProfile,
    ): ExistingUserMatch {
        val linked =
            socialAccountRepository.findByProviderIdentity(
                tenantId = tenantId,
                provider = provider,
                providerUserId = profile.providerUserId,
            )
        if (linked != null) {
            val user = userRepository.findById(linked.userId, tenantId) ?: return ExistingUserMatch.None
            return ExistingUserMatch.Linked(user)
        }

        val email = profile.email?.trim()?.lowercase() ?: return ExistingUserMatch.None
        val existingByEmail = userRepository.findByEmail(tenantId, email) ?: return ExistingUserMatch.None
        if (!profile.emailVerified) return ExistingUserMatch.EmailCollisionUnverified

        socialAccountRepository.save(
            SocialAccount(
                userId = existingByEmail.id!!,
                tenantId = tenantId,
                provider = provider,
                providerUserId = profile.providerUserId,
                providerEmail = profile.email,
                providerName = profile.name,
                avatarUrl = profile.avatarUrl,
            ),
        )
        return ExistingUserMatch.Linked(existingByEmail)
    }

    /**
     * Issues tokens for a resolved user and persists the session + audit log.
     * Shared by both handleCallback() and completeSocialRegistration().
     */
    private fun issueTokens(
        user: User,
        tenant: Tenant,
        provider: ProviderKey,
        isNewUser: Boolean,
        ipAddress: String?,
        userAgent: String?,
    ): SocialLoginResult<SocialLoginSuccess> {
        if (!user.enabled) {
            auditLog.record(
                AuditEvent(
                    tenantId = tenant.id,
                    userId = user.id,
                    clientId = null,
                    eventType = AuditEventType.LOGIN_FAILED,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    details = mapOf("provider" to provider.value),
                ),
            )
            return SocialLoginResult.Failure(SocialLoginError.UserDisabled)
        }

        val tokens =
            tokenPort.issueUserTokens(
                user = user,
                tenant = tenant,
                client = null,
                scopes = listOf("openid"),
            )

        sessionRepository.save(
            Session(
                tenantId = tenant.id,
                userId = user.id!!,
                clientId = null,
                accessTokenHash = sha256Hex(tokens.access_token),
                refreshTokenHash = tokens.refresh_token?.let { sha256Hex(it) },
                scopes = "openid",
                ipAddress = ipAddress,
                userAgent = userAgent,
                expiresAt = Instant.now().plusSeconds(tenant.tokenExpirySeconds),
                refreshExpiresAt =
                    tokens.refresh_token?.let {
                        Instant.now().plusSeconds(tenant.refreshTokenExpirySeconds)
                    },
            ),
        )

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = null,
                eventType = AuditEventType.LOGIN_SUCCESS,
                ipAddress = ipAddress,
                userAgent = userAgent,
                details = mapOf("provider" to provider.value, "new_user" to isNewUser.toString()),
            ),
        )

        return SocialLoginResult.Success(
            SocialLoginSuccess(
                tokens = tokens,
                user = user,
                isNewUser = isNewUser,
            ),
        )
    }

    private fun generateRandomPassword(): String = SecureTokens.randomHex(32)

    private fun callbackUri(
        baseUrl: String,
        tenantSlug: String,
        provider: ProviderKey,
    ): String = "$baseUrl/t/$tenantSlug/auth/social/${provider.value}/callback"
}

// ---------------------------------------------------------------------------
// Result types
// ---------------------------------------------------------------------------

data class SocialLoginSuccess(
    val tokens: TokenResponse,
    val user: User,
    val isNewUser: Boolean,
)

data class SocialLoginNeedsRegistration(
    val provider: ProviderKey,
    val providerUserId: String,
    val email: String,
    val name: String?,
    val avatarUrl: String?,
    val emailVerified: Boolean,
)

sealed class SocialLoginResult<out T> {
    data class Success<T>(
        val value: T,
    ) : SocialLoginResult<T>()

    data class Failure(
        val error: SocialLoginError,
    ) : SocialLoginResult<Nothing>()

    data class NeedsRegistration(
        val data: SocialLoginNeedsRegistration,
    ) : SocialLoginResult<Nothing>()
}

sealed class SocialLoginError {
    object TenantNotFound : SocialLoginError()

    object ProviderNotConfigured : SocialLoginError()

    object EmailNotProvided : SocialLoginError()

    object UserDisabled : SocialLoginError()

    object AccountCreationFailed : SocialLoginError()

    object RegistrationDisabled : SocialLoginError()

    object UsernameConflict : SocialLoginError()

    object LinkRequiresEmailVerification : SocialLoginError()

    data class InvalidUsername(
        val reason: String,
    ) : SocialLoginError()

    data class ProviderError(
        val message: String,
    ) : SocialLoginError()

    data class InternalError(
        val message: String,
    ) : SocialLoginError()
}
