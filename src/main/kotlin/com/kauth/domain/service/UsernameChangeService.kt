package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant

sealed interface UsernameChangeResult {
    data class Changed(
        val user: User,
    ) : UsernameChangeResult

    /** The workspace keeps usernames administrator-controlled. */
    data object Disabled : UsernameChangeResult

    data object TooManyAttempts : UsernameChangeResult

    data class CoolingDown(
        val nextAllowedAt: Instant,
    ) : UsernameChangeResult

    data object Unchanged : UsernameChangeResult

    data class Invalid(
        val message: String,
    ) : UsernameChangeResult

    data class Taken(
        val message: String,
    ) : UsernameChangeResult

    data object NotFound : UsernameChangeResult
}

/**
 * A user renaming their own account. Every attempt counts against [attemptLimiter], so the
 * uniqueness check can't be used to list which usernames exist, and a successful rename starts a
 * [COOLDOWN] during which no other self-rename goes through.
 */
class UsernameChangeService(
    private val users: UserRepository,
    private val tenants: TenantRepository,
    private val auditLog: AuditLogPort,
    private val attemptLimiter: RateLimiterPort,
    collisionCheck: IdentifierCollisionCheck = IdentifierCollisionCheck(users),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val rules = UsernameRules(users, collisionCheck)

    fun isEnabled(tenantId: TenantId): Boolean =
        tenants.findById(tenantId)?.securityConfig?.usernameChangeEnabled == true

    /** When [user] may next rename themselves, or null when they may now. */
    fun nextAllowedAt(user: User): Instant? =
        user.usernameSelfChangedAt
            ?.plus(COOLDOWN)
            ?.takeIf { it.isAfter(Instant.now(clock)) }

    fun changeOwn(
        tenantId: TenantId,
        userId: UserId,
        requested: String,
        ipAddress: String?,
    ): UsernameChangeResult {
        if (!isEnabled(tenantId)) return UsernameChangeResult.Disabled
        if (!attemptLimiter.isAllowed("${tenantId.value}:${userId.value}")) {
            return UsernameChangeResult.TooManyAttempts
        }
        val user = users.findById(userId, tenantId) ?: return UsernameChangeResult.NotFound
        nextAllowedAt(user)?.let { return UsernameChangeResult.CoolingDown(it) }
        val username = UsernamePolicy.normalize(requested)
        if (username == user.username) return UsernameChangeResult.Unchanged
        when (val check = rules.checkRename(tenantId, userId, username, user.email)) {
            is UsernameRules.Check.Invalid -> return UsernameChangeResult.Invalid(check.message)
            is UsernameRules.Check.Taken -> return UsernameChangeResult.Taken(check.message)
            is UsernameRules.Check.Ok -> Unit
        }
        val renamed = users.renameBySelf(userId, username, Instant.now(clock))
        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.USER_USERNAME_CHANGED,
                ipAddress = ipAddress,
                userAgent = null,
                details = mapOf("username" to user.username, "newUsername" to username),
            ),
        )
        return UsernameChangeResult.Changed(renamed)
    }

    companion object {
        val COOLDOWN: Duration = Duration.ofDays(30)
    }
}
