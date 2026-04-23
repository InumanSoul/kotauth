package com.kauth.domain.service

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserAttribute
import com.kauth.domain.model.UserId
import com.kauth.domain.port.UserAttributeRepository
import com.kauth.domain.port.UserRepository
import java.time.Instant

/**
 * Domain service — per-user key/value attribute management.
 *
 * Values are opaque strings; keys are opaque to KotAuth. The only validation
 * is length enforcement. Tenant isolation is verified by loading the user and
 * comparing tenant IDs before every write.
 *
 * Attribute changes are eventually consistent for token consumers — bounded by
 * the access-token TTL. Callers that need immediate enforcement must validate
 * server-side on each request.
 */
class UserAttributeService(
    private val userAttributeRepository: UserAttributeRepository,
    private val userRepository: UserRepository,
) {
    fun list(
        userId: UserId,
        tenantId: TenantId,
    ): AttributeResult<Map<String, String>> {
        if (userRepository.findById(userId, tenantId) == null) {
            return AttributeResult.NotFound("user")
        }
        return AttributeResult.Success(userAttributeRepository.findAll(userId, tenantId))
    }

    fun upsert(
        userId: UserId,
        tenantId: TenantId,
        key: String,
        value: String,
    ): AttributeResult<UserAttribute> {
        if (key.isBlank()) {
            return AttributeResult.ValidationError("Attribute key must not be blank.")
        }
        if (key.length > UserAttribute.MAX_KEY_LENGTH) {
            return AttributeResult.ValidationError(
                "Attribute key must be at most ${UserAttribute.MAX_KEY_LENGTH} characters.",
            )
        }
        if (value.length > UserAttribute.MAX_VALUE_LENGTH) {
            return AttributeResult.ValidationError(
                "Attribute value must be at most ${UserAttribute.MAX_VALUE_LENGTH} characters.",
            )
        }
        if (userRepository.findById(userId, tenantId) == null) {
            return AttributeResult.NotFound("user")
        }
        val attribute =
            UserAttribute(
                userId = userId,
                tenantId = tenantId,
                key = key,
                value = value,
                updatedAt = Instant.now(),
            )
        userAttributeRepository.upsert(attribute)
        return AttributeResult.Success(attribute)
    }

    fun delete(
        userId: UserId,
        tenantId: TenantId,
        key: String,
    ): AttributeResult<Unit> {
        if (userRepository.findById(userId, tenantId) == null) {
            return AttributeResult.NotFound("user")
        }
        userAttributeRepository.delete(userId, tenantId, key)
        return AttributeResult.Success(Unit)
    }
}

/**
 * Sealed result type for user-attribute and claim-mapper operations.
 * Services never throw for business errors — callers pattern-match on this.
 */
sealed class AttributeResult<out T> {
    data class Success<T>(
        val value: T,
    ) : AttributeResult<T>()

    data class NotFound(
        val resource: String,
    ) : AttributeResult<Nothing>()

    data class ValidationError(
        val reason: String,
    ) : AttributeResult<Nothing>()

    data class ReservedClaimName(
        val claimName: String,
    ) : AttributeResult<Nothing>()

    data class DuplicateClaimName(
        val claimName: String,
    ) : AttributeResult<Nothing>()

    data class LimitReached(
        val max: Int,
    ) : AttributeResult<Nothing>()
}
