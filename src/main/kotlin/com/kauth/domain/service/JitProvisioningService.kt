package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.BrokeredReferenceHasher
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
import java.net.IDN

/** The outcome of the just-in-time trust gate for one brokered sign-in. */
sealed interface JitOutcome {
    data class Provisioned(
        val user: User,
    ) : JitOutcome

    /**
     * The gate refused. [reason] is operator-facing and says which rule turned this sign-in away;
     * [reference] is the same handle the audit row carries, computed once here so the page the
     * person is shown and the row the operator reads cannot disagree.
     */
    data class Refused(
        val reason: JitRefusal,
        val reference: String,
    ) : JitOutcome

    /** JIT is off for this provider — fall through to the existing registration flow. */
    data object NotEnabled : JitOutcome
}

enum class JitRefusal {
    EMAIL_NOT_VERIFIED,
    DOMAIN_NOT_ALLOWED,

    /** A local account already holds the username this would take — the address itself. */
    USERNAME_CONFLICT,
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
    private val references: BrokeredReferenceHasher,
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
        // An absent `email_verified` claim reads as false, and some major issuers never emit it,
        // so the strict gate refuses every sign-in from them. Trusting the claim is opt-in per
        // provider and does not widen the domain allowlist below.
        if (!profile.emailVerified && !provider.trustEmailClaim) {
            return refuse(tenant, provider, profile, JitRefusal.EMAIL_NOT_VERIFIED, ipAddress, userAgent)
        }

        val email =
            profile.email
                ?.trim()
                ?.lowercase()
                ?.ifBlank { null }
        // The address is also the username (see below), so it must satisfy UsernamePolicy too —
        // an IdP-supplied local part is not guaranteed to. Folded into the same refusal bucket as
        // the domain check rather than a new category: both mean "this address is not one we can
        // provision an account for here", and a distinct message would only tell an attacker more
        // about which half of the address rule tripped.
        val addressUsable =
            email != null &&
                isDomainAllowed(email, provider.jitAllowedDomains) &&
                UsernamePolicy.isValid(email)
        if (!addressUsable) {
            return refuse(tenant, provider, profile, JitRefusal.DOMAIN_NOT_ALLOWED, ipAddress, userAgent)
        }

        // The username is the address, and an admin-created username may contain '@'. Without this
        // check the insert violates UNIQUE (tenant_id, username) and the exception leaves the
        // domain as a 500 on every retry, with nothing on the diagnostics panel to explain it.
        // The caller has already established that no user holds this address as an email, so this
        // can only be a different person whose username happens to be it.
        if (userRepository.existsByUsername(tenant.id, email)) {
            return refuse(tenant, provider, profile, JitRefusal.USERNAME_CONFLICT, ipAddress, userAgent)
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
        val reference = references.of(tenant.id, provider.provider, profile.providerUserId)
        val details =
            buildMap {
                put(BrokeredSignInFailure.PROVIDER, provider.provider.value)
                put(
                    BrokeredSignInFailure.REASON,
                    when (reason) {
                        JitRefusal.EMAIL_NOT_VERIFIED -> BrokeredSignInFailure.EMAIL_NOT_VERIFIED
                        JitRefusal.DOMAIN_NOT_ALLOWED -> BrokeredSignInFailure.DOMAIN_NOT_ALLOWED
                        JitRefusal.USERNAME_CONFLICT -> BrokeredSignInFailure.USERNAME_CONFLICT
                    },
                )
                put(BrokeredSignInFailure.REFERENCE, reference)
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
        return JitOutcome.Refused(reason, reference)
    }

    /**
     * Exact, on the whole domain, with both sides first reduced to their ASCII (punycode) form.
     *
     * A suffix test for `oriana.com.py` would also accept `evil-oriana.com.py`, a domain anyone can
     * register. `equals(ignoreCase = true)` was not exactness either: the JDK folds characters that
     * are not case variants of each other, so `"oriana.com.py".equalsIgnoreCase("orıana.com.py")`
     * with a dotless i is true while `==` is false. Reducing to one canonical spelling first is
     * what also stops an A-label standing in for a domain nobody listed.
     */
    private fun isDomainAllowed(
        email: String,
        allowed: List<String>,
    ): Boolean {
        // Exactly one '@': "a@evil.example@oriana.com.py" must not read as the allowed domain.
        if (email.count { it == '@' } != 1) return false
        val domain = asciiDomain(email.substringAfterLast('@', "")) ?: return false
        return allowed.any { asciiDomain(it) == domain }
    }

    /** The A-label spelling, so a U-label and its punycode cannot be two different domains. */
    private fun asciiDomain(value: String): String? {
        val trimmed = value.trim().lowercase()
        if (trimmed.isEmpty()) return null
        return runCatching { IDN.toASCII(trimmed) }
            .getOrNull()
            ?.lowercase()
            ?.ifBlank { null }
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
