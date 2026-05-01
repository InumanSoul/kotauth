package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TokenPort
import com.kauth.domain.port.UserRepository
import com.kauth.domain.util.sha256Hex
import java.time.Instant

/**
 * Application use cases for admin impersonation (RFC 8693 token exchange model).
 *
 * The acting admin keeps their own session intact; a parallel impersonation
 * session is issued for the target user, with [Session.impersonatorSessionId]
 * pointing back to the admin's session row. Tokens carry a nested `act` claim
 * so downstream APIs can attribute actions to the originating admin.
 *
 * Authorization is enforced upstream — by virtue of holding an `AdminSession`
 * in the master tenant. This service trusts the [adminUserId] / [adminSessionId]
 * pair handed to it and only validates target-side correctness.
 */
class ImpersonationService(
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val sessionRepository: SessionRepository,
    private val tokenPort: TokenPort,
    private val auditLog: AuditLogPort,
) {
    data class StartResult(
        val tokens: TokenResponse,
        val impersonationSessionId: SessionId,
        val targetUserId: UserId,
        val targetTenantId: TenantId,
    )

    /**
     * Issues a new session for [targetUserId] in [targetTenantId] and links it
     * back to the admin's session via [Session.impersonatorSessionId].
     *
     * Refuses to impersonate users in the master tenant (admin-on-admin),
     * disabled users, or users with a temporary lockout.
     */
    fun startImpersonation(
        adminUserId: UserId,
        adminSessionId: SessionId,
        targetTenantId: TenantId,
        targetUserId: UserId,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): AdminResult<StartResult> {
        val tenant =
            tenantRepository.findById(targetTenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("Tenant not found"))
        if (tenant.isMaster) {
            return AdminResult.Failure(
                AdminError.Validation("Master-tenant users cannot be impersonated"),
            )
        }

        val target =
            userRepository.findById(targetUserId, targetTenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("User not found"))
        if (!target.enabled) {
            return AdminResult.Failure(AdminError.Validation("User is disabled"))
        }
        if (target.isLocked) {
            return AdminResult.Failure(AdminError.Validation("User is temporarily locked"))
        }

        val adminSession =
            sessionRepository.findById(adminSessionId)
                ?: return AdminResult.Failure(AdminError.NotFound("Admin session not found"))
        if (!adminSession.isActive || adminSession.userId != adminUserId) {
            return AdminResult.Failure(AdminError.Validation("Admin session is not active"))
        }

        val tokens =
            tokenPort.issueUserTokens(
                user = target,
                tenant = tenant,
                client = null,
                scopes = listOf("openid"),
                actingSubject = adminUserId,
            )

        val now = Instant.now()
        val saved =
            sessionRepository.save(
                Session(
                    tenantId = tenant.id,
                    userId = target.id,
                    clientId = null,
                    accessTokenHash = sha256Hex(tokens.access_token),
                    refreshTokenHash = tokens.refresh_token?.let { sha256Hex(it) },
                    scopes = "openid",
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    createdAt = now,
                    expiresAt = now.plusSeconds(tenant.tokenExpirySeconds),
                    refreshExpiresAt =
                        tokens.refresh_token?.let {
                            now.plusSeconds(tenant.refreshTokenExpirySeconds)
                        },
                    lastActivityAt = now,
                    impersonatorSessionId = adminSessionId,
                ),
            )

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = adminUserId,
                clientId = null,
                eventType = AuditEventType.ADMIN_IMPERSONATION_STARTED,
                ipAddress = ipAddress,
                userAgent = userAgent,
                details =
                    mapOf(
                        "target_user_id" to target.id!!.value.toString(),
                        "target_username" to target.username,
                        "admin_session_id" to adminSessionId.value.toString(),
                        "impersonation_session_id" to saved.id!!.value.toString(),
                    ),
            ),
        )

        return AdminResult.Success(
            StartResult(
                tokens = tokens,
                impersonationSessionId = saved.id,
                targetUserId = target.id,
                targetTenantId = tenant.id,
            ),
        )
    }

    /**
     * Revokes an active impersonation session and emits the audit event.
     * Idempotent: a second call on the same session returns Validation.
     */
    fun stopImpersonation(
        adminUserId: UserId,
        adminSessionId: SessionId,
        impersonationSessionId: SessionId,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): AdminResult<Unit> {
        val session =
            sessionRepository.findById(impersonationSessionId)
                ?: return AdminResult.Failure(AdminError.NotFound("Impersonation session not found"))
        if (session.impersonatorSessionId != adminSessionId) {
            return AdminResult.Failure(AdminError.Validation("Session is not owned by this admin"))
        }
        if (!session.isActive) {
            return AdminResult.Failure(AdminError.Validation("Session is not active"))
        }

        sessionRepository.revoke(impersonationSessionId, Instant.now())

        auditLog.record(
            AuditEvent(
                tenantId = session.tenantId,
                userId = adminUserId,
                clientId = null,
                eventType = AuditEventType.ADMIN_IMPERSONATION_ENDED,
                ipAddress = ipAddress,
                userAgent = userAgent,
                details =
                    mapOf(
                        "target_user_id" to (session.userId?.value?.toString() ?: ""),
                        "admin_session_id" to adminSessionId.value.toString(),
                        "impersonation_session_id" to impersonationSessionId.value.toString(),
                    ),
            ),
        )

        return AdminResult.Success(Unit)
    }
}
