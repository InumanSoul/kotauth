package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantKey
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.TenantKeyRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TokenPort
import com.kauth.infrastructure.KeyGenerator

/**
 * Domain service for admin-initiated RSA key rotation.
 *
 * Rotation generates a new key pair, promotes it to active (signing),
 * and demotes the old key to verification-only (still served via JWKS).
 * Tokens signed by the old key continue to verify until the key is retired.
 */
class KeyRotationService(
    private val tenantKeyRepository: TenantKeyRepository,
    private val tenantRepository: TenantRepository,
    private val tokenPort: TokenPort,
    private val auditLog: AuditLogPort,
) {
    /**
     * Generates a new RS256 key pair and promotes it to the active signing key.
     * The previous key remains enabled for verification (served via JWKS).
     */
    fun rotate(
        tenantId: TenantId,
        performedBy: UserId,
    ): AdminResult<TenantKey> {
        val tenant =
            tenantRepository.findById(tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace not found."))

        val currentActive =
            tenantKeyRepository.findActiveKey(tenantId)
                ?: return AdminResult.Failure(
                    AdminError.Validation("No active key to rotate from. Provision a key first."),
                )

        val newKeyPair =
            KeyGenerator.generateRsaKeyPair(
                keyId = "${tenant.slug}-${System.currentTimeMillis()}",
            )

        val newKey =
            tenantKeyRepository.save(
                TenantKey(
                    tenantId = tenantId,
                    keyId = newKeyPair.keyId,
                    publicKeyPem = newKeyPair.publicKeyPem,
                    privateKeyPem = newKeyPair.privateKeyPem,
                    enabled = true,
                    active = false,
                ),
            )

        tenantKeyRepository.rotate(
            tenantId = tenantId,
            newKeyId = newKey.keyId,
            previousKeyId = currentActive.keyId,
        )

        tokenPort.invalidateSigningKeyCache(tenantId)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = performedBy,
                clientId = null,
                eventType = AuditEventType.ADMIN_KEY_ROTATED,
                ipAddress = null,
                userAgent = null,
                details =
                    mapOf(
                        "new_key_id" to newKey.keyId,
                        "previous_key_id" to currentActive.keyId,
                    ),
            ),
        )

        return AdminResult.Success(newKey.copy(active = true))
    }

    /**
     * Retires a specific key — removes it from JWKS.
     * The active signing key cannot be retired; rotate first.
     */
    fun retireKey(
        tenantId: TenantId,
        keyId: String,
        performedBy: UserId,
    ): AdminResult<Unit> {
        val key =
            tenantKeyRepository.findByKeyId(tenantId, keyId)
                ?: return AdminResult.Failure(AdminError.NotFound("Key not found."))
        if (key.active) {
            return AdminResult.Failure(
                AdminError.Validation("Cannot retire the active signing key. Rotate first."),
            )
        }

        tenantKeyRepository.disable(tenantId, keyId)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = performedBy,
                clientId = null,
                eventType = AuditEventType.ADMIN_KEY_RETIRED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("retired_key_id" to keyId),
            ),
        )

        return AdminResult.Success(Unit)
    }
}
