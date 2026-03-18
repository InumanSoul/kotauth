package com.kauth.domain.service

import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.port.ApiKeyRepository
import com.kauth.domain.port.TenantRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Domain service — API key lifecycle management (Phase 3a).
 *
 * Responsibilities:
 *   - Generate new keys (raw value returned once, hash stored)
 *   - Validate incoming keys on every REST request
 *   - Revoke / delete keys via admin console
 *
 * Key format: `kauth_<tenantSlug>_<32-random-bytes-base64url>`
 *   Example:   `kauth_acme_4wQk2n8...`
 *
 * Verification is O(1): SHA-256 hash lookup by indexed column.
 * No bcrypt — key entropy (256 bits) makes timing attacks infeasible,
 * and SHA-256 is fast enough not to require rate-limiting at the hash level.
 */
class ApiKeyService(
    private val apiKeyRepository: ApiKeyRepository,
    private val tenantRepository: TenantRepository,
) {
    // =========================================================================
    // Key generation
    // =========================================================================

    /**
     * Creates a new API key for [tenantId].
     *
     * @param name      Human-readable label (e.g. "CI pipeline").
     * @param scopes    List of [ApiScope] strings. Unknown scopes are silently dropped.
     * @param expiresAt Optional absolute expiry — null means the key never expires.
     *
     * @return [ApiKeyResult.Created] with the persisted [ApiKey] and the one-time [rawKey].
     *         The [rawKey] is never stored — the caller MUST surface it to the user immediately.
     */
    fun create(
        tenantId: Int,
        name: String,
        scopes: List<String>,
        expiresAt: Instant? = null,
    ): ApiKeyResult<CreatedApiKey> {
        if (name.isBlank()) {
            return ApiKeyResult.Failure(ApiKeyError.Validation("API key name is required."))
        }
        if (name.length > 128) {
            return ApiKeyResult.Failure(ApiKeyError.Validation("API key name must be 128 characters or fewer."))
        }

        val tenant =
            tenantRepository.findById(tenantId)
                ?: return ApiKeyResult.Failure(ApiKeyError.NotFound("Tenant $tenantId not found."))

        // Validate and filter scopes
        val validScopes = scopes.filter { it in ApiScope.ALL }
        if (validScopes.isEmpty()) {
            return ApiKeyResult.Failure(ApiKeyError.Validation("At least one valid scope is required."))
        }

        // Build raw key: kauth_<slug>_<32 random bytes base64url, no padding>
        val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        val rawKey = "kauth_${tenant.slug}_$randomPart"

        val hash = sha256Hex(rawKey)
        val prefix = rawKey.take(16) // e.g. "kauth_acme_4wQk2" — enough to identify in UI

        val saved =
            apiKeyRepository.save(
                ApiKey(
                    tenantId = tenantId,
                    name = name.trim(),
                    keyPrefix = prefix,
                    keyHash = hash,
                    scopes = validScopes,
                    expiresAt = expiresAt,
                    enabled = true,
                ),
            )

        return ApiKeyResult.Success(CreatedApiKey(apiKey = saved, rawKey = rawKey))
    }

    // =========================================================================
    // Key validation (called on every authenticated API request)
    // =========================================================================

    /**
     * Validates a raw Bearer token as an API key for [expectedTenantId].
     *
     * Checks:
     *   1. Key exists by hash
     *   2. Belongs to the correct tenant
     *   3. Is enabled
     *   4. Has not expired
     *
     * Updates [ApiKey.lastUsedAt] on success (best-effort, non-transactional).
     *
     * @return The validated [ApiKey] on success, or null on any failure.
     */
    fun validate(
        rawKey: String,
        expectedTenantId: Int,
    ): ApiKey? {
        val hash = sha256Hex(rawKey)
        val apiKey = apiKeyRepository.findByHash(hash) ?: return null

        if (apiKey.tenantId != expectedTenantId) return null
        if (!apiKey.enabled) return null
        if (apiKey.expiresAt != null && apiKey.expiresAt.isBefore(Instant.now())) return null

        // Best-effort touch — don't block the request if this fails
        runCatching { apiKeyRepository.touchLastUsed(apiKey.id!!, Instant.now()) }

        return apiKey
    }

    // =========================================================================
    // Admin operations
    // =========================================================================

    fun listForTenant(tenantId: Int): List<ApiKey> = apiKeyRepository.findByTenantId(tenantId)

    fun revoke(
        id: Int,
        tenantId: Int,
    ): ApiKeyResult<Unit> {
        apiKeyRepository.findById(id, tenantId)
            ?: return ApiKeyResult.Failure(ApiKeyError.NotFound("API key not found."))
        apiKeyRepository.revoke(id, tenantId)
        return ApiKeyResult.Success(Unit)
    }

    fun delete(
        id: Int,
        tenantId: Int,
    ): ApiKeyResult<Unit> {
        apiKeyRepository.findById(id, tenantId)
            ?: return ApiKeyResult.Failure(ApiKeyError.NotFound("API key not found."))
        apiKeyRepository.delete(id, tenantId)
        return ApiKeyResult.Success(Unit)
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

// =============================================================================
// Result types
// =============================================================================

/**
 * Carries both the persisted [ApiKey] and the one-time plaintext [rawKey].
 * Surface [rawKey] to the user immediately — it cannot be recovered later.
 */
data class CreatedApiKey(
    val apiKey: ApiKey,
    val rawKey: String,
)

sealed class ApiKeyResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ApiKeyResult<T>()

    data class Failure(
        val error: ApiKeyError,
    ) : ApiKeyResult<Nothing>()
}

sealed class ApiKeyError(
    val message: String,
) {
    class NotFound(
        message: String,
    ) : ApiKeyError(message)

    class Validation(
        message: String,
    ) : ApiKeyError(message)
}
