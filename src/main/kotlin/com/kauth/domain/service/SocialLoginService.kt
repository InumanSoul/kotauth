package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Session
import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.SocialProvider
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

/**
 * Domain service for social (OAuth2) login.
 *
 * Flow:
 *   1. Route layer calls [buildRedirectUrl] -> user is sent to provider (Google/GitHub)
 *   2. Provider redirects back with ?code=...&state=...
 *   3. Route layer calls [handleCallback] with the code + verified tenant/provider
 *   4. Service exchanges code for profile, finds/creates local user, issues tokens
 *
 * Account linking strategy - email-based within the same tenant:
 *   a) Existing social_account row matches (tenant, provider, providerUserId) -> reuse linked user.
 *   b) Local user with same email exists -> auto-link the provider to that user.
 *   c) Otherwise -> create new user (no usable password; social-only).
 */
class SocialLoginService(
    private val identityProviderRepository : IdentityProviderRepository,
    private val socialAccountRepository    : SocialAccountRepository,
    private val userRepository             : UserRepository,
    private val tenantRepository           : TenantRepository,
    private val sessionRepository          : SessionRepository,
    private val tokenPort                  : TokenPort,
    private val passwordHasher             : PasswordHasher,
    private val auditLog                   : AuditLogPort,
    private val providerAdapters           : Map<SocialProvider, SocialProviderPort>
) {

    /**
     * Builds the provider authorization URL that the browser should be redirected to.
     */
    fun buildRedirectUrl(
        tenantSlug : String,
        provider   : SocialProvider,
        state      : String,
        baseUrl    : String
    ): SocialLoginResult<String> {
        val tenant = tenantRepository.findBySlug(tenantSlug)
            ?: return SocialLoginResult.Failure(SocialLoginError.TenantNotFound)

        val idp = identityProviderRepository.findByTenantAndProvider(tenant.id, provider)
        if (idp == null || !idp.enabled) {
            return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)
        }

        val adapter = providerAdapters[provider]
            ?: return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)

        val url = adapter.buildAuthorizationUrl(
            clientId    = idp.clientId,
            redirectUri = callbackUri(baseUrl, tenantSlug, provider),
            state       = state
        )
        return SocialLoginResult.Success(url)
    }

    /**
     * Processes the OAuth2 callback. The route layer must verify the state signature
     * and extract tenantSlug + provider before calling this.
     */
    fun handleCallback(
        tenantSlug : String,
        provider   : SocialProvider,
        code       : String,
        baseUrl    : String,
        ipAddress  : String? = null,
        userAgent  : String? = null
    ): SocialLoginResult<SocialLoginSuccess> {
        val tenant = tenantRepository.findBySlug(tenantSlug)
            ?: return SocialLoginResult.Failure(SocialLoginError.TenantNotFound)

        val idp = identityProviderRepository.findByTenantAndProvider(tenant.id, provider)
        if (idp == null || !idp.enabled) {
            return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)
        }

        val adapter = providerAdapters[provider]
            ?: return SocialLoginResult.Failure(SocialLoginError.ProviderNotConfigured)

        val profile = try {
            adapter.exchangeCodeForProfile(
                code         = code,
                redirectUri  = callbackUri(baseUrl, tenantSlug, provider),
                clientId     = idp.clientId,
                clientSecret = idp.clientSecret
            )
        } catch (e: Exception) {
            return SocialLoginResult.Failure(
                SocialLoginError.ProviderError("Failed to exchange authorization code: ${e.message}")
            )
        }

        if (profile.email.isNullOrBlank()) {
            return SocialLoginResult.Failure(SocialLoginError.EmailNotProvided)
        }

        val (user, isNewUser) = resolveUser(tenant.id, provider, profile)
            ?: return SocialLoginResult.Failure(SocialLoginError.AccountCreationFailed)

        if (!user.enabled) {
            auditLog.record(AuditEvent(
                tenantId  = tenant.id,
                userId    = user.id,
                clientId  = null,
                eventType = AuditEventType.LOGIN_FAILED,
                ipAddress = ipAddress,
                userAgent = userAgent,
                details   = mapOf("provider" to provider.value)
            ))
            return SocialLoginResult.Failure(SocialLoginError.UserDisabled)
        }

        val tokens = tokenPort.issueUserTokens(
            user   = user,
            tenant = tenant,
            client = null,
            scopes = listOf("openid")
        )

        sessionRepository.save(Session(
            tenantId         = tenant.id,
            userId           = user.id!!,
            clientId         = null,
            accessTokenHash  = sha256(tokens.access_token),
            refreshTokenHash = tokens.refresh_token?.let { sha256(it) },
            scopes           = "openid",
            ipAddress        = ipAddress,
            userAgent        = userAgent,
            expiresAt        = Instant.now().plusSeconds(tenant.tokenExpirySeconds),
            refreshExpiresAt = tokens.refresh_token?.let {
                Instant.now().plusSeconds(tenant.refreshTokenExpirySeconds)
            }
        ))

        auditLog.record(AuditEvent(
            tenantId  = tenant.id,
            userId    = user.id,
            clientId  = null,
            eventType = AuditEventType.LOGIN_SUCCESS,
            ipAddress = ipAddress,
            userAgent = userAgent,
            details   = mapOf("provider" to provider.value, "new_user" to isNewUser.toString())
        ))

        return SocialLoginResult.Success(SocialLoginSuccess(
            tokens    = tokens,
            user      = user,
            isNewUser = isNewUser
        ))
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun resolveUser(
        tenantId : Int,
        provider : SocialProvider,
        profile  : SocialUserProfile
    ): Pair<User, Boolean>? {
        // (a) Known social account
        val existing = socialAccountRepository.findByProviderIdentity(
            tenantId       = tenantId,
            provider       = provider,
            providerUserId = profile.providerUserId
        )
        if (existing != null) {
            return (userRepository.findById(existing.userId) ?: return null) to false
        }

        val email = profile.email?.trim()?.lowercase() ?: return null

        // (b) Local user with same email - auto-link
        val existingUser = userRepository.findByEmail(tenantId, email)
        if (existingUser != null) {
            socialAccountRepository.save(SocialAccount(
                userId         = existingUser.id!!,
                tenantId       = tenantId,
                provider       = provider,
                providerUserId = profile.providerUserId,
                providerEmail  = profile.email,
                providerName   = profile.name,
                avatarUrl      = profile.avatarUrl
            ))
            return existingUser to false
        }

        // (c) Create new user
        val username = generateUniqueUsername(tenantId, profile)
        val newUser = userRepository.save(User(
            tenantId      = tenantId,
            username      = username,
            email         = email,
            fullName      = profile.name?.trim()?.ifBlank { null } ?: username,
            passwordHash  = passwordHasher.hash(generateRandomPassword()),
            emailVerified = profile.emailVerified
        ))
        socialAccountRepository.save(SocialAccount(
            userId         = newUser.id!!,
            tenantId       = tenantId,
            provider       = provider,
            providerUserId = profile.providerUserId,
            providerEmail  = profile.email,
            providerName   = profile.name,
            avatarUrl      = profile.avatarUrl
        ))
        return newUser to true
    }

    private fun generateUniqueUsername(tenantId: Int, profile: SocialUserProfile): String {
        val base = profile.email
            ?.substringBefore("@")
            ?.replace(Regex("[^a-zA-Z0-9_]"), "")
            ?.lowercase()
            ?.take(32)
            ?.ifBlank { null }
            ?: "user"

        if (!userRepository.existsByUsername(tenantId, base)) return base

        repeat(8) {
            val candidate = "${base}_${(1000..9999).random()}"
            if (!userRepository.existsByUsername(tenantId, candidate)) return candidate
        }
        return "${base}_${System.currentTimeMillis()}"
    }

    private fun generateRandomPassword(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun callbackUri(baseUrl: String, tenantSlug: String, provider: SocialProvider): String =
        "$baseUrl/t/$tenantSlug/auth/social/${provider.value}/callback"

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

// ---------------------------------------------------------------------------
// Result types
// ---------------------------------------------------------------------------

data class SocialLoginSuccess(
    val tokens    : TokenResponse,
    val user      : User,
    val isNewUser : Boolean
)

sealed class SocialLoginResult<out T> {
    data class Success<T>(val value: T) : SocialLoginResult<T>()
    data class Failure(val error: SocialLoginError) : SocialLoginResult<Nothing>()
}

sealed class SocialLoginError {
    object TenantNotFound        : SocialLoginError()
    object ProviderNotConfigured : SocialLoginError()
    object EmailNotProvided      : SocialLoginError()
    object UserDisabled          : SocialLoginError()
    object AccountCreationFailed : SocialLoginError()
    data class ProviderError(val message: String) : SocialLoginError()
    data class InternalError(val message: String) : SocialLoginError()
}
