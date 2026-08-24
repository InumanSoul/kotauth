package com.kauth.domain.service

import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.port.ApiKeyRepository
import com.kauth.domain.port.TenantRepository

/**
 * Idempotently provisions admin API keys from infrastructure config.
 *
 * Called at boot from `Application.kt` with entries decoded from the
 * `KAUTH_BOOTSTRAP_API_KEYS` env var. Each entry is upserted by
 * (tenant, name) — re-running with the same hash is a no-op; changing the
 * hash rotates the key in place.
 *
 * Unknown tenant slugs cause a fail-fast result so a typo can't silently
 * leave a partner BFF without its key at startup. Unknown scopes are
 * rejected for the same reason.
 *
 * The entry is authoritative for every field it owns, the SCIM dialect included: an entry that
 * names no dialect re-asserts the `rfc` default, so the environment and the running key cannot
 * drift apart across a restart.
 */
class ApiKeyBootstrapService(
    private val apiKeyRepository: ApiKeyRepository,
    private val tenantRepository: TenantRepository,
) {
    data class Entry(
        val tenantSlug: String,
        val name: String,
        val scopes: List<String>,
        val keyHash: String,
        val keyPrefix: String? = null,
        /** Optional; null means the RFC pass-through. Validated against the registry before it gets here. */
        val scimDialect: String? = null,
    )

    companion object {
        internal fun defaultKeyPrefix(tenantSlug: String): String = "kauth_$tenantSlug".take(16)
    }

    sealed class Result {
        data class Provisioned(
            val applied: List<Outcome>,
        ) : Result()

        data class Failure(
            val message: String,
        ) : Result()
    }

    data class Outcome(
        val tenantSlug: String,
        val name: String,
        val action: Action,
    )

    enum class Action { CREATED, UPDATED, UNCHANGED }

    fun ensureBootstrapped(entries: List<Entry>): Result {
        val outcomes = mutableListOf<Outcome>()
        for (entry in entries) {
            val tenant =
                tenantRepository.findBySlug(entry.tenantSlug)
                    ?: return Result.Failure(
                        "Unknown tenant slug '${entry.tenantSlug}' for bootstrap key '${entry.name}'",
                    )

            val invalidScope = entry.scopes.firstOrNull { it !in ApiScope.ALL }
            if (invalidScope != null) {
                return Result.Failure(
                    "Unknown scope '$invalidScope' for bootstrap key '${entry.name}'",
                )
            }

            val resolvedPrefix = (entry.keyPrefix ?: defaultKeyPrefix(entry.tenantSlug)).take(16)
            val resolvedDialect = entry.scimDialect ?: ApiKey.DEFAULT_SCIM_DIALECT
            val existing = apiKeyRepository.findByTenantAndName(tenant.id, entry.name)
            if (existing == null) {
                apiKeyRepository.save(
                    ApiKey(
                        tenantId = tenant.id,
                        name = entry.name,
                        keyPrefix = resolvedPrefix,
                        keyHash = entry.keyHash,
                        scopes = entry.scopes,
                        enabled = true,
                        bootstrapName = entry.name,
                        scimDialect = resolvedDialect,
                    ),
                )
                outcomes += Outcome(entry.tenantSlug, entry.name, Action.CREATED)
                continue
            }

            val hashUnchanged = existing.keyHash == entry.keyHash
            val scopesUnchanged = existing.scopes.toSet() == entry.scopes.toSet()
            val brandedAsBootstrap = existing.bootstrapName == entry.name
            val enabled = existing.enabled
            val dialectUnchanged = existing.scimDialect == resolvedDialect

            if (hashUnchanged && scopesUnchanged && brandedAsBootstrap && enabled && dialectUnchanged) {
                outcomes += Outcome(entry.tenantSlug, entry.name, Action.UNCHANGED)
                continue
            }

            apiKeyRepository.updateBootstrap(
                id = existing.id!!,
                keyHash = entry.keyHash,
                scopes = entry.scopes,
                bootstrapName = entry.name,
                scimDialect = resolvedDialect,
            )
            outcomes += Outcome(entry.tenantSlug, entry.name, Action.UPDATED)
        }
        return Result.Provisioned(outcomes)
    }
}
