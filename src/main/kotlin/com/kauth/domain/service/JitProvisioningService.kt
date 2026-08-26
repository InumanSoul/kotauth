package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.BrokeredSignInFailure
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SocialAccountRepository
import com.kauth.domain.port.SocialUserProfile
import com.kauth.domain.port.UserRepository

/** The outcome of the just-in-time trust gate for one brokered sign-in. */
sealed interface JitOutcome {
    data class Provisioned(
        val user: User,
    ) : JitOutcome

    /** The gate refused. [reason] is operator-facing and distinguishes the three refusals. */
    data class Refused(
        val reason: JitRefusal,
    ) : JitOutcome

    /** JIT is off for this provider — fall through to the existing registration flow. */
    data object NotEnabled : JitOutcome
}

enum class JitRefusal {
    EMAIL_NOT_VERIFIED,
    DOMAIN_NOT_ALLOWED,
}

/**
 * Creates a local account on a first sign-in through a brokered provider, and only then.
 *
 * Three conditions must all hold: the provider has JIT switched on, the provider itself asserts
 * the email is verified, and the email's domain is one the tenant listed. An empty list is the
 * feature switched off, never a wildcard.
 *
 * The service only ever creates. Callers must have already established that no local user matches
 * this identity — an email that matches an existing account is that account's to govern, or an
 * IdP willing to assert an address becomes an account-takeover primitive.
 */
class JitProvisioningService(
    private val userRepository: UserRepository,
    private val socialAccountRepository: SocialAccountRepository,
    private val auditLog: AuditLogPort,
    private val applicationRepository: ApplicationRepository? = null,
    private val roleRepository: RoleRepository? = null,
) {
    fun provision(
        tenant: Tenant,
        provider: IdentityProvider,
        profile: SocialUserProfile,
        originatingClientId: String? = null,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): JitOutcome {
        if (!provider.jitEnabled) return JitOutcome.NotEnabled
        if (!profile.emailVerified) {
            return refuse(tenant, provider, profile, JitRefusal.EMAIL_NOT_VERIFIED, ipAddress, userAgent)
        }

        val email =
            profile.email
                ?.trim()
                ?.lowercase()
                ?.ifBlank { null }
        if (email == null || !isDomainAllowed(email, provider.jitAllowedDomains)) {
            return refuse(tenant, provider, profile, JitRefusal.DOMAIN_NOT_ALLOWED, ipAddress, userAgent)
        }

        val user =
            userRepository.save(
                User(
                    tenantId = tenant.id,
                    // The email is the username so a SCIM connector later wired to the same IdP
                    // finds this user by `userName` instead of creating a parallel duplicate.
                    username = email,
                    email = email,
                    fullName = fullNameOf(profile, email),
                    passwordHash = User.SENTINEL_PASSWORD_HASH,
                    // `external_id` is SCIM's correlation key; the provider identity is linked in
                    // social_accounts instead, so both subsystems can hold the same person.
                    externalId = null,
                    givenName = profile.givenName?.trim()?.ifBlank { null },
                    familyName = profile.familyName?.trim()?.ifBlank { null },
                    emailVerified = true,
                    enabled = true,
                ),
            )

        val userId = user.id!!
        socialAccountRepository.save(
            SocialAccount(
                userId = userId,
                tenantId = tenant.id,
                provider = provider.provider,
                providerUserId = profile.providerUserId,
                providerEmail = email,
                providerName = profile.name,
                avatarUrl = profile.avatarUrl,
            ),
        )

        applyClientDefaultRolesGrant(
            tenant.id,
            userId,
            originatingClientId,
            applicationRepository,
            roleRepository,
        )

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.JIT_USER_PROVISIONED,
                ipAddress = ipAddress,
                userAgent = userAgent,
                details =
                    mapOf(
                        "provider" to provider.provider.value,
                        "provider_user_id" to profile.providerUserId,
                    ),
            ),
        )

        return JitOutcome.Provisioned(user)
    }

    /**
     * Refuses, and leaves the operator a row to diagnose from.
     *
     * The refusal is the whole record: no address, no provider subject, no code — see
     * [BrokeredSignInFailure] for why each is left out and what stands in for them.
     */
    private fun refuse(
        tenant: Tenant,
        provider: IdentityProvider,
        profile: SocialUserProfile,
        reason: JitRefusal,
        ipAddress: String?,
        userAgent: String?,
    ): JitOutcome.Refused {
        val details =
            buildMap {
                put(BrokeredSignInFailure.PROVIDER, provider.provider.value)
                put(
                    BrokeredSignInFailure.REASON,
                    when (reason) {
                        JitRefusal.EMAIL_NOT_VERIFIED -> BrokeredSignInFailure.EMAIL_NOT_VERIFIED
                        JitRefusal.DOMAIN_NOT_ALLOWED -> BrokeredSignInFailure.DOMAIN_NOT_ALLOWED
                    },
                )
                put(
                    BrokeredSignInFailure.REFERENCE,
                    BrokeredSignInFailure.reference(tenant.id, provider.provider, profile.providerUserId),
                )
                BrokeredSignInFailure.emailDomainOf(profile.email)?.let {
                    put(BrokeredSignInFailure.EMAIL_DOMAIN, it)
                }
            }
        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = null,
                clientId = null,
                eventType = AuditEventType.SOCIAL_LOGIN_FAILED,
                ipAddress = ipAddress,
                userAgent = userAgent,
                details = details,
            ),
        )
        return JitOutcome.Refused(reason)
    }

    /**
     * Exact, case-insensitive, on the whole domain. A suffix test for `oriana.com.py` would also
     * accept `evil-oriana.com.py`, which is a domain anyone can register.
     */
    private fun isDomainAllowed(
        email: String,
        allowed: List<String>,
    ): Boolean {
        // Exactly one '@': "a@evil.example@oriana.com.py" must not read as the allowed domain.
        if (email.count { it == '@' } != 1) return false
        val domain = email.substringAfterLast('@', "")
        if (domain.isEmpty()) return false
        return allowed.any { it.trim().equals(domain, ignoreCase = true) }
    }

    private fun fullNameOf(
        profile: SocialUserProfile,
        email: String,
    ): String =
        profile.name?.trim()?.ifBlank { null }
            ?: listOfNotNull(profile.givenName?.trim(), profile.familyName?.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .ifBlank { null }
            ?: email.substringBefore('@')
}
