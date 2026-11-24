package com.kauth.domain.service

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.AuthMethods
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.EmailOtpChallenge
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.workspacePageUrl
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.AuthorizationCodeRepository
import com.kauth.domain.port.EmailOtpChallengeRepository
import com.kauth.domain.port.EmailPort
import com.kauth.domain.port.ResourceServerRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.util.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Email-OTP passwordless primitive. Owned by Phase 2 of v1.12.0.
 *
 * `sendOtp` is find-or-create-and-send (the creation half is gated by
 * [com.kauth.domain.model.SecurityConfig.emailOtpSignupEnabled]; the response
 * shape is uniform regardless). `verifyOtp` mints a single-use OAuth
 * authorization code that the caller exchanges at the standard `/token`
 * endpoint — the BFF never sees the user id, only the opaque challenge handle
 * and the code.
 *
 * Constant-time padding is a route-layer concern (the route wraps the call with `delay()`). The
 * code is mailed on [emailScope], off the request path, so the padding covers every branch: a
 * synchronous SMTP round-trip outlasts it and would tell a known address from an unknown one.
 */
class EmailOtpService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val challengeRepository: EmailOtpChallengeRepository,
    private val applicationRepository: ApplicationRepository,
    private val authorizationCodeRepository: AuthorizationCodeRepository,
    private val emailPort: EmailPort,
    private val auditLog: AuditLogPort,
    private val resourceServerRepository: ResourceServerRepository,
    private val roleRepository: RoleRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val usernameGenerator: UsernameGenerator = UsernameGenerator(userRepository),
    private val emailScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    // KAUTH_BASE_URL, for links into a workspace that has no public domain of its own.
    private val instanceBaseUrl: String = "",
) {
    private val log = LoggerFactory.getLogger(EmailOtpService::class.java)

    companion object {
        const val OTP_TTL_MINUTES: Long = 10
        const val MAX_ATTEMPTS_PER_CHALLENGE: Int = 5
        const val AUTH_CODE_TTL_SECONDS: Long = 60

        /** The scope a code carries when the client sent none: what every code carried before #160. */
        const val DEFAULT_SCOPE = "openid email profile"

        /** How long after the email code is verified the second factor may still complete. */
        const val VERIFIED_CHALLENGE_TTL_SECONDS: Long = 300
        private val SECURE_RANDOM = SecureRandom()
    }

    fun sendOtp(
        tenantSlug: String,
        email: String,
        originatingClientId: String? = null,
        resources: List<String> = emptyList(),
        ipAddress: String? = null,
        scope: String? = null,
    ): OtpSendResult {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return OtpSendResult.Failure(OtpError.TenantNotFound)

        val normalizedEmail = email.trim().lowercase()
        val existing = userRepository.findByEmail(tenant.id, normalizedEmail)
        val now = clock.instant()
        val expiresAt = now.plusSeconds(OTP_TTL_MINUTES * 60)

        val user =
            existing ?: run {
                // Signup disabled, or this exact address is already someone ELSE's username (a
                // cross-namespace collision `IdentifierCollisionCheck` guards elsewhere). Either
                // way: uniform success with a handle that never resolves, so this endpoint stays a
                // non-oracle for both cases alike.
                if (!tenant.securityConfig.emailOtpSignupEnabled) {
                    return OtpSendResult.Success(generateChallengeHandle(), expiresAt)
                }
                if (userRepository.existsByUsername(tenant.id, normalizedEmail)) {
                    log.info("OTP sign-up skipped: tenant={} reason=address_is_another_username", tenant.slug)
                    return OtpSendResult.Success(generateChallengeHandle(), expiresAt)
                }
                userRepository.save(
                    User(
                        tenantId = tenant.id,
                        username = usernameFor(tenant.id, normalizedEmail),
                        email = normalizedEmail,
                        fullName = normalizedEmail.substringBefore("@"),
                        passwordHash = User.SENTINEL_PASSWORD_HASH,
                        emailVerified = false,
                        requiredActions = emptySet<RequiredAction>(),
                    ),
                )
            }

        val invalidated = challengeRepository.deleteActiveByUser(user.id!!)
        val rawCode = generateSixDigitCode()
        val challenge =
            challengeRepository.create(
                EmailOtpChallenge(
                    userId = user.id,
                    tenantId = tenant.id,
                    challengeId = generateChallengeHandle(),
                    codeHash = sha256Hex(rawCode),
                    originatingClientId = originatingClientId,
                    resources = resources.normalizedResources(),
                    scope = scope?.normalizedScope(),
                    resendCount = if (invalidated > 0) 1 else 0,
                    expiresAt = expiresAt,
                    createdAt = now,
                ),
            )

        emailScope.launch {
            val deliveryFailure =
                runCatching {
                    emailPort.sendEmailOtpEmail(
                        to = user.email,
                        toName = user.fullName,
                        code = rawCode,
                        expiresInMinutes = OTP_TTL_MINUTES,
                        workspaceName = tenant.emailBranding?.brandName ?: tenant.displayName,
                        tenant = tenant,
                    )
                }.exceptionOrNull()
            if (deliveryFailure != null) {
                log.warn(
                    "OTP email delivery failed: tenant={} email={} reason={}",
                    tenant.slug,
                    user.email,
                    deliveryFailure.javaClass.simpleName,
                )
            }
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = null,
                eventType = AuditEventType.EMAIL_OTP_SENT,
                ipAddress = ipAddress,
                userAgent = null,
                details =
                    mapOf(
                        "resend_count" to challenge.resendCount.toString(),
                        "originating_client_id" to (originatingClientId ?: ""),
                        "resources" to Json.encodeToString(challenge.resources),
                    ),
            ),
        )

        return OtpSendResult.Success(challenge.challengeId, challenge.expiresAt)
    }

    fun verifyOtp(
        tenantSlug: String,
        challengeId: String,
        code: String,
        ipAddress: String? = null,
    ): OtpVerifyResult {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return OtpVerifyResult.Failure(OtpError.TenantNotFound)

        val challenge = challengeRepository.findByChallengeId(challengeId)
        if (challenge == null || challenge.tenantId != tenant.id) {
            return reject(tenant.id, null, null, OtpError.InvalidOtp, ipAddress)
        }

        val user = userRepository.findById(challenge.userId, tenant.id)
        if (user == null) {
            return reject(tenant.id, challenge.userId, null, OtpError.InvalidOtp, ipAddress)
        }

        if (isLocked(user)) {
            return reject(tenant.id, user.id, null, OtpError.TooManyAttempts, ipAddress)
        }

        if (challenge.isConsumed || challenge.attemptCount >= MAX_ATTEMPTS_PER_CHALLENGE) {
            return reject(tenant.id, user.id, null, OtpError.TooManyAttempts, ipAddress)
        }

        if (clock.instant().isAfter(challenge.expiresAt)) {
            return reject(tenant.id, user.id, null, OtpError.ChallengeExpired, ipAddress)
        }

        val attempts = challengeRepository.incrementAttempts(challenge.id!!)
        val expectedDigest = challenge.codeHash.toByteArray(Charsets.UTF_8)
        val providedDigest = sha256Hex(code.trim()).toByteArray(Charsets.UTF_8)
        val matches = MessageDigest.isEqual(expectedDigest, providedDigest)

        if (!matches) {
            return if (attempts >= MAX_ATTEMPTS_PER_CHALLENGE) {
                trackCrossChallengeFailure(tenant, user, ipAddress)
                reject(tenant.id, user.id, null, OtpError.TooManyAttempts, ipAddress)
            } else {
                reject(tenant.id, user.id, null, OtpError.InvalidOtp, ipAddress)
            }
        }

        challengeRepository.markConsumed(challenge.id, clock.instant())
        if (!user.emailVerified) {
            userRepository.update(user.copy(emailVerified = true))
        }
        userRepository.resetFailedOtpChallenges(user.id!!)

        applyClientDefaultRolesGrant(
            tenant.id,
            user.id,
            challenge.originatingClientId,
            applicationRepository,
            roleRepository,
        )

        val originatingClient =
            challenge.originatingClientId?.let { applicationRepository.findByClientId(tenant.id, it) }
        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = originatingClient?.id,
                eventType = AuditEventType.EMAIL_OTP_VERIFIED,
                ipAddress = ipAddress,
                userAgent = null,
                details = mapOf("challenge_id" to challenge.challengeId),
            ),
        )

        return OtpVerifyResult.Success(user.id, challenge.challengeId, challenge.originatingClientId)
    }

    /**
     * Issues the authorization code for a challenge whose email code was already verified, once
     * the caller has settled the second factor. Verifying the code and issuing the authorization
     * code are separate steps so that nothing a client can redeem exists before MFA passes.
     * [secondFactorDone] records that the caller verified a second factor, for the `amr` claim.
     */
    fun issueAuthorizationCode(
        tenantSlug: String,
        challengeId: String,
        userId: UserId,
        secondFactorDone: Boolean = false,
    ): OtpCodeResult {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return OtpCodeResult.Failure(OtpError.TenantNotFound)
        val challenge = challengeRepository.findByChallengeId(challengeId)
        if (challenge == null || challenge.tenantId != tenant.id || challenge.userId != userId) {
            return OtpCodeResult.Failure(OtpError.InvalidOtp)
        }
        val verifiedAt = challenge.consumedAt ?: return OtpCodeResult.Failure(OtpError.InvalidOtp)
        if (clock.instant().isAfter(verifiedAt.plusSeconds(VERIFIED_CHALLENGE_TTL_SECONDS))) {
            return OtpCodeResult.Failure(OtpError.ChallengeExpired)
        }
        return issueAuthorizationCodeFor(tenant, userId, challenge, secondFactorDone)
    }

    private fun trackCrossChallengeFailure(
        tenant: Tenant,
        user: User,
        ipAddress: String?,
    ) {
        val threshold = tenant.securityConfig.emailOtpLockoutThreshold
        if (threshold <= 0) return

        val newCount = user.failedOtpChallenges + 1
        if (newCount < threshold) {
            userRepository.recordFailedOtpChallenge(user.id!!, newCount, null)
            return
        }

        val lockMinutes = tenant.securityConfig.lockoutDurationMinutes.coerceAtLeast(1)
        val lockUntil = clock.instant().plusSeconds(lockMinutes * 60L)
        userRepository.recordFailedOtpChallenge(user.id!!, 0, lockUntil)

        runCatching {
            emailPort.sendAccountLockedEmail(
                to = user.email,
                toName = user.fullName,
                resetUrl = passwordResetUrl(tenant),
                workspaceName = tenant.emailBranding?.brandName ?: tenant.displayName,
                lockoutDuration = "$lockMinutes minutes",
                tenant = tenant,
            )
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = null,
                eventType = AuditEventType.EMAIL_OTP_LOCKOUT,
                ipAddress = ipAddress,
                userAgent = null,
                details =
                    mapOf(
                        "reason" to "verify_otp_abuse",
                        "threshold" to threshold.toString(),
                        "lockout_minutes" to lockMinutes.toString(),
                    ),
            ),
        )
    }

    // Back-channel issuance — PKCE not required (ADR-15). The scope is narrowed exactly as the token
    // endpoint narrows it (ADR-23), so a code is never issued for a scope its exchange would refuse.
    private fun issueAuthorizationCodeFor(
        tenant: Tenant,
        userId: UserId,
        challenge: EmailOtpChallenge,
        secondFactorDone: Boolean,
    ): OtpCodeResult {
        val invalidClient = OtpCodeResult.Failure(OtpError.InvalidClient)
        val clientIdString = challenge.originatingClientId
        if (clientIdString.isNullOrBlank()) return invalidClient
        val client = applicationRepository.findByClientId(tenant.id, clientIdString) ?: return invalidClient
        if (!client.enabled) return invalidClient
        val redirectUri = client.redirectUris.firstOrNull() ?: return invalidClient
        val resources = challengeResourcesFor(tenant, client.id, challenge.resources) ?: return invalidClient
        val requestedScopes = (challenge.scope ?: DEFAULT_SCOPE).split(' ').filter { it.isNotBlank() }
        val narrowing =
            ScopeNarrower.narrow(
                requestedScopes,
                resources,
                resourceServerRepository.findAllowedScopes(client.id),
            )
        if (narrowing is OAuthService.ScopeNarrowing.InvalidScope) {
            return OtpCodeResult.Failure(OtpError.InvalidScope)
        }

        val now = clock.instant()
        val authCode =
            authorizationCodeRepository.save(
                AuthorizationCode(
                    code = generateAuthCode(),
                    tenantId = tenant.id,
                    clientId = client.id,
                    userId = userId,
                    redirectUri = redirectUri,
                    scopes = requestedScopes.joinToString(" "),
                    expiresAt = now.plusSeconds(AUTH_CODE_TTL_SECONDS),
                    createdAt = now,
                    authTime = now,
                    resources = resources.map { it.identifier },
                    amr =
                        listOf(AuthMethods.ONE_TIME_CODE).let {
                            if (secondFactorDone) AuthMethods.withSecondFactor(it) else it
                        },
                ),
            )
        return OtpCodeResult.Success(authCode.code, authCode.expiresAt, authCode.redirectUri)
    }

    private fun challengeResourcesFor(
        tenant: Tenant,
        clientPk: ApplicationId,
        requestedResources: List<String>,
    ): List<ResourceServer>? {
        val challengeResources = requestedResources.normalizedResources()
        if (challengeResources.isEmpty()) return emptyList()
        val authorized = resourceServerRepository.listAuthorizedFor(clientPk).map { it.identifier }.toSet()
        val resourcesByIdentifier =
            resourceServerRepository
                .findByIdentifiers(tenant.id, challengeResources.toSet())
                .associateBy { it.identifier }
        return challengeResources.map { identifier ->
            resourcesByIdentifier[identifier]?.takeIf { it.enabled && identifier in authorized } ?: return null
        }
    }

    private fun List<String>.normalizedResources(): List<String> =
        map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun String.normalizedScope(): String? =
        split(' ')
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { null }

    private fun reject(
        tenantId: TenantId,
        userId: UserId?,
        clientId: com.kauth.domain.model.ApplicationId?,
        error: OtpError,
        ipAddress: String?,
    ): OtpVerifyResult.Failure {
        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = clientId,
                eventType = AuditEventType.EMAIL_OTP_REJECTED,
                ipAddress = ipAddress,
                userAgent = null,
                details = mapOf("reason" to error.code),
            ),
        )
        return OtpVerifyResult.Failure(error)
    }

    private fun isLocked(user: User): Boolean = user.lockedUntil?.isAfter(clock.instant()) == true

    private fun generateSixDigitCode(): String = "%06d".format(SECURE_RANDOM.nextInt(1_000_000))

    /** The address itself when it is username-shaped; otherwise a generated handle. */
    private fun usernameFor(
        tenantId: TenantId,
        email: String,
    ): String = if (UsernamePolicy.isValid(email)) email else usernameGenerator.generate(tenantId, null, email)

    private fun generateChallengeHandle(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateAuthCode(): String = "${UUID.randomUUID()}${UUID.randomUUID()}".replace("-", "")

    private fun passwordResetUrl(tenant: Tenant): String =
        workspacePageUrl(tenant, instanceBaseUrl, "/account/forgot-password")
}

sealed class OtpSendResult {
    data class Success(
        val challengeId: String,
        val expiresAt: Instant,
    ) : OtpSendResult()

    data class Failure(
        val error: OtpError,
    ) : OtpSendResult()
}

sealed class OtpVerifyResult {
    data class Success(
        val userId: UserId,
        val challengeId: String,
        val originatingClientId: String?,
    ) : OtpVerifyResult()

    data class Failure(
        val error: OtpError,
    ) : OtpVerifyResult()
}

sealed class OtpCodeResult {
    data class Success(
        val authorizationCode: String,
        val codeExpiresAt: Instant,
        val redirectUri: String,
    ) : OtpCodeResult()

    data class Failure(
        val error: OtpError,
    ) : OtpCodeResult()
}

enum class OtpError(
    val code: String,
) {
    TenantNotFound("tenant_not_found"),
    InvalidOtp("invalid_otp"),
    ChallengeExpired("challenge_expired"),
    TooManyAttempts("too_many_attempts"),
    InvalidClient("invalid_client"),
    InvalidScope("invalid_scope"),
}
