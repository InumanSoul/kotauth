package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.RelyingPartyAdapter
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.port.WebAuthnCredentialRepository
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

class WebAuthnService(
    private val credentialRepository: WebAuthnCredentialRepository,
    private val relyingParty: RelyingPartyAdapter,
    private val secretKey: String,
    private val auditLog: AuditLogPort,
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Derives a stable 32-byte user handle for WebAuthn from the tenant + user + secret key.
     * The handle is deterministic and opaque — it does not reveal the user's database ID.
     */
    fun deriveUserHandle(
        tenantId: TenantId,
        userId: UserId,
    ): ByteArray {
        val input = "${tenantId.value}:${userId.value}:$secretKey".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    fun startRegistration(
        user: User,
        tenant: Tenant,
    ): WebAuthnResult<RegistrationOptions> {
        if (!tenant.passkeysEnabled) return WebAuthnResult.Failure(WebAuthnError.PasskeysDisabledForTenant)
        val userId = requireNotNull(user.id) { "user must be persisted before passkey operations" }
        val existing = credentialRepository.findByUserId(userId, tenant.id)
        val excludeIds = existing.map { it.credentialId }
        val userHandle = deriveUserHandle(tenant.id, userId)
        val (optionsJson, challenge) =
            relyingParty.startRegistration(
                userHandle = userHandle,
                username = user.username,
                displayName = user.fullName.ifBlank { user.username },
                excludeCredentialIds = excludeIds,
            )
        return WebAuthnResult.Success(RegistrationOptions(optionsJson, challenge))
    }

    fun finishRegistration(
        user: User,
        tenant: Tenant,
        creationOptionsJson: String,
        request: RegistrationFinishRequest,
    ): WebAuthnResult<WebAuthnCredential> {
        if (!tenant.passkeysEnabled) return WebAuthnResult.Failure(WebAuthnError.PasskeysDisabledForTenant)
        val userId = requireNotNull(user.id) { "user must be persisted before passkey operations" }
        val parsed =
            try {
                relyingParty.finishRegistration(creationOptionsJson, request.credentialJson)
            } catch (e: IllegalStateException) {
                return WebAuthnResult.Failure(WebAuthnError.VerificationFailed(e.message ?: "verification failed"))
            }
        val credential =
            WebAuthnCredential(
                userId = userId,
                tenantId = tenant.id,
                credentialId = parsed.credentialId,
                publicKeyCose = parsed.publicKeyCose,
                signCounter = parsed.signCounter,
                aaguid = parsed.aaguid,
                transports = parsed.transports,
                name = request.name.trim().take(64),
                backupEligible = parsed.backupEligible,
                backupState = parsed.backupState,
                createdAt = Instant.now(clock),
                lastUsedAt = null,
            )
        val saved = credentialRepository.save(credential)
        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.PASSKEY_ENROLLED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("credentialId" to saved.id.toString()),
            ),
        )
        return WebAuthnResult.Success(saved)
    }

    fun startAuthentication(tenant: Tenant): WebAuthnResult<AuthenticationOptions> {
        if (!tenant.passkeysEnabled) return WebAuthnResult.Failure(WebAuthnError.PasskeysDisabledForTenant)
        val (optionsJson, challenge) = relyingParty.startAssertion()
        return WebAuthnResult.Success(AuthenticationOptions(optionsJson, challenge))
    }

    /**
     * Verifies a passkey assertion. Yubico's [finishAssertion] internally calls the
     * injected CredentialRepository bridge (Task 6) to fetch the stored credential
     * during cryptographic verification — no pre-fetch needed here.
     * After verification, the credential is looked up by credentialId for tenant
     * match enforcement, user resolution, and sign-counter update.
     */
    fun finishAuthentication(
        tenant: Tenant,
        assertionRequestJson: String,
        request: AuthenticationFinishRequest,
    ): WebAuthnResult<AuthenticationOutcome> {
        if (!tenant.passkeysEnabled) return WebAuthnResult.Failure(WebAuthnError.PasskeysDisabledForTenant)

        val assertion =
            try {
                relyingParty.finishAssertion(assertionRequestJson, request.credentialJson)
            } catch (e: IllegalStateException) {
                auditLog.record(
                    AuditEvent(
                        tenantId = tenant.id,
                        userId = null,
                        clientId = null,
                        eventType = AuditEventType.PASSKEY_AUTH_FAILED,
                        ipAddress = null,
                        userAgent = null,
                        details = mapOf("reason" to (e.message ?: "verification failed")),
                    ),
                )
                return WebAuthnResult.Failure(WebAuthnError.VerificationFailed(e.message ?: "verification failed"))
            }

        val stored =
            credentialRepository.findByCredentialId(assertion.credentialId)
                ?: return WebAuthnResult.Failure(WebAuthnError.CredentialNotFound)

        if (stored.tenantId != tenant.id) return WebAuthnResult.Failure(WebAuthnError.TenantMismatch)

        if (assertion.newSignCounter <= stored.signCounter && stored.signCounter > 0) {
            credentialRepository.delete(stored.id!!, stored.userId)
            auditLog.record(
                AuditEvent(
                    tenantId = tenant.id,
                    userId = stored.userId,
                    clientId = null,
                    eventType = AuditEventType.PASSKEY_REPLAY_REJECTED,
                    ipAddress = null,
                    userAgent = null,
                    details = mapOf("credentialId" to stored.id.toString()),
                ),
            )
            return WebAuthnResult.Failure(WebAuthnError.CounterReplayDetected)
        }

        val user =
            userRepository.findById(stored.userId, tenant.id)
                ?: return WebAuthnResult.Failure(WebAuthnError.CredentialNotFound)

        if (!user.enabled) return WebAuthnResult.Failure(WebAuthnError.UserDisabled)

        credentialRepository.updateCounter(stored.id!!, assertion.newSignCounter, Instant.now(clock))
        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = stored.userId,
                clientId = null,
                eventType = AuditEventType.PASSKEY_AUTH_SUCCESS,
                ipAddress = null,
                userAgent = null,
                details =
                    mapOf(
                        "credentialId" to stored.id.toString(),
                        "userVerified" to assertion.userVerified.toString(),
                    ),
            ),
        )

        return WebAuthnResult.Success(AuthenticationOutcome(stored.userId, stored, assertion.userVerified))
    }

    fun listForUser(
        userId: UserId,
        tenantId: TenantId,
    ): List<WebAuthnCredential> = credentialRepository.findByUserId(userId, tenantId)

    fun rename(
        userId: UserId,
        credentialPk: Long,
        newName: String,
    ): WebAuthnResult<Unit> {
        val trimmed = newName.trim().take(64)
        if (trimmed.isBlank()) return WebAuthnResult.Failure(WebAuthnError.VerificationFailed("name required"))
        return if (credentialRepository.rename(credentialPk, userId, trimmed)) {
            WebAuthnResult.Success(Unit)
        } else {
            WebAuthnResult.Failure(WebAuthnError.CredentialNotFound)
        }
    }

    fun revoke(
        userId: UserId,
        credentialPk: Long,
        tenantId: TenantId,
    ): WebAuthnResult<Unit> {
        val tenant = tenantRepository?.findById(tenantId)
        if (tenant?.passwordLoginDisabled == true) {
            val remaining = credentialRepository.findByUserId(userId, tenantId)
            if (remaining.size == 1 && remaining.first().id == credentialPk) {
                return WebAuthnResult.Failure(WebAuthnError.CannotRevokeLast)
            }
        }
        return if (credentialRepository.delete(credentialPk, userId)) {
            auditLog.record(
                AuditEvent(
                    tenantId = tenantId,
                    userId = userId,
                    clientId = null,
                    eventType = AuditEventType.PASSKEY_REVOKED,
                    ipAddress = null,
                    userAgent = null,
                    details = mapOf("credentialId" to credentialPk.toString()),
                ),
            )
            WebAuthnResult.Success(Unit)
        } else {
            WebAuthnResult.Failure(WebAuthnError.CredentialNotFound)
        }
    }

    fun adminResetAll(
        tenantId: TenantId,
        userId: UserId,
        actorId: UserId,
    ): WebAuthnResult<Int> {
        val count = credentialRepository.deleteAllByUserId(userId, tenantId)
        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = actorId,
                clientId = null,
                eventType = AuditEventType.PASSKEY_ADMIN_RESET_ALL,
                ipAddress = null,
                userAgent = null,
                details =
                    mapOf(
                        "targetUser" to userId.value.toString(),
                        "count" to count.toString(),
                    ),
            ),
        )
        return WebAuthnResult.Success(count)
    }
}

sealed class WebAuthnResult<out T> {
    data class Success<T>(
        val value: T,
    ) : WebAuthnResult<T>()

    data class Failure(
        val error: WebAuthnError,
    ) : WebAuthnResult<Nothing>()
}

sealed class WebAuthnError {
    data object InvalidChallenge : WebAuthnError()

    data object CredentialNotFound : WebAuthnError()

    data object CounterReplayDetected : WebAuthnError()

    data object UserDisabled : WebAuthnError()

    data object TenantDisabled : WebAuthnError()

    data object TenantMismatch : WebAuthnError()

    data object PasskeysDisabledForTenant : WebAuthnError()

    data class VerificationFailed(
        val reason: String,
    ) : WebAuthnError()

    data class RateLimited(
        val retryAfter: java.time.Duration,
    ) : WebAuthnError()

    data object CannotRevokeLast : WebAuthnError()
}

/**
 * Outcome of a successful passkey authentication.
 * [userId] and [credential] identify who authenticated with which device.
 * [userVerified] reflects whether the authenticator performed user verification (PIN/biometric).
 */
data class AuthenticationOutcome(
    val userId: UserId,
    val credential: WebAuthnCredential,
    val userVerified: Boolean,
)

data class RegistrationOptions(
    val publicKeyOptionsJson: String,
    val challenge: String,
)

data class AuthenticationOptions(
    val publicKeyOptionsJson: String,
    val challenge: String,
)

data class RegistrationFinishRequest(
    val credentialJson: String,
    val name: String,
)

data class AuthenticationFinishRequest(
    val credentialJson: String,
)
