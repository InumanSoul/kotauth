package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Session
import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.SocialAccountRepository
import com.kauth.domain.port.SocialProviderPort
import com.kauth.domain.port.SocialUserProfile
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TokenPort
import com.kauth.domain.port.UserRepository
import com.kauth.domain.util.sha256Hex
import java.security.SecureRandom
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
    private val providerAdapters: Map<SocialProvider, SocialProviderPort>,
) {
    /**
     * Builds the provider authorization URL that the browser should be redirected to.
     */
    fun buildRedirectUrl(
        tenantSlug: String,
        provider: SocialProvider,
        state: String,
        baseUrl: String,
    ): SocialLoginResult<String> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return SocialLoginResult.Failure(SocialLoginError.TenantNotFound)

        val idp = identityProviderRepository.findByTenantAndProvider(tenant.id, provider)
        if (idp == null || !idp.enabled) {
            return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)
        }

        val adapter =
            providerAdapters[provider]
                ?: return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)

        val url =
            adapter.buildAuthorizationUrl(
                clientId = idp.clientId,
                redirectUri = callbackUri(baseUrl, tenantSlug, provider),
                state = state,
            )
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
        provider: SocialProvider,
        code: String,
        baseUrl: String,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): SocialLoginResult<SocialLoginSuccess> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return SocialLoginResult.Failure(SocialLoginError.TenantNotFound)

        val idp = identityProviderRepository.findByTenantAndProvider(tenant.id, provider)
        if (idp == null || !idp.enabled) {
            return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)
        }

        val adapter =
            providerAdapters[provider]
                ?: return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)

        val profile =
            try {
                adapter.exchangeCodeForProfile(
                    code = code,
                    redirectUri = callbackUri(baseUrl, tenantSlug, provider),
                    clientId = idp.clientId,
                    clientSecret = idp.clientSecret,
                )
            } catch (e: Exception) {
                return SocialLoginResult.Failure(
                    SocialLoginError.ProviderError("Failed to exchange authorization code: ${e.message}"),
                )
            }

        if (profile.email.isNullOrBlank()) {
            return SocialLoginResult.Failure(SocialLoginError.EmailNotProvided)
        }

        // Try to find an existing user — never create one here.
        val existingUser = findExistingUser(tenant.id, provider, profile)

        if (existingUser == null) {
            // No account linked and no email match — hand off to the registration completion flow.
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

        return issueTokens(existingUser, tenant, provider, isNewUser = false, ipAddress, userAgent)
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
        provider: SocialProvider,
        providerUserId: String,
        email: String,
        providerName: String?,
        avatarUrl: String?,
        emailVerified: Boolean,
        chosenUsername: String,
        ipAddress: String? = null,
        userAgent: String? = null,
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
            userRepository.findById(existingLink.userId)?.let { user ->
                return issueTokens(user, tenant, provider, isNewUser = false, ipAddress, userAgent)
            }
        }

        val normalizedEmail = email.trim().lowercase()
        val existingByEmail = userRepository.findByEmail(tenant.id, normalizedEmail)
        if (existingByEmail != null) {
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

        return issueTokens(newUser, tenant, provider, isNewUser = true, ipAddress, userAgent)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Looks up an existing KotAuth user by social account link or email match.
     * NEVER creates a new user. NEVER modifies an existing user's profile.
     *
     *   (a) social_accounts row matches (tenantId, provider, providerUserId) -> return linked user.
     *   (b) users row with matching email exists in this tenant -> auto-link + return user.
     *   else -> return null (caller decides what to do — typically: show registration page).
     */
    private fun findExistingUser(
        tenantId: TenantId,
        provider: SocialProvider,
        profile: SocialUserProfile,
    ): User? {
        // (a) Known social account link
        val existing =
            socialAccountRepository.findByProviderIdentity(
                tenantId = tenantId,
                provider = provider,
                providerUserId = profile.providerUserId,
            )
        if (existing != null) {
            return userRepository.findById(existing.userId)
        }

        val email = profile.email?.trim()?.lowercase() ?: return null

        // (b) Email match — auto-link without touching the user's profile
        val existingUser = userRepository.findByEmail(tenantId, email) ?: return null
        socialAccountRepository.save(
            SocialAccount(
                userId = existingUser.id!!,
                tenantId = tenantId,
                provider = provider,
                providerUserId = profile.providerUserId,
                providerEmail = profile.email,
                providerName = profile.name,
                avatarUrl = profile.avatarUrl,
            ),
        )
        return existingUser
    }

    /**
     * Issues tokens for a resolved user and persists the session + audit log.
     * Shared by both handleCallback() and completeSocialRegistration().
     */
    private fun issueTokens(
        user: User,
        tenant: Tenant,
        provider: SocialProvider,
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

    private fun generateRandomPassword(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun callbackUri(
        baseUrl: String,
        tenantSlug: String,
        provider: SocialProvider,
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
    val provider: SocialProvider,
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
