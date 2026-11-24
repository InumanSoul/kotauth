package com.kauth.domain.service

import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.port.ApiKeyRepository
import com.kauth.domain.port.TenantRepository

/**
 * Idempotently provisions admin API keys from infrastructure config.
 *
 * Called at boot from `Application.kt` with entries decoded from the
 * `KAUTH_BOOTSTRAP_API_KEYS` env var. Each entry is upserted by
 * (tenant, name) — re-running with the same hash is a no-op; changing the
 * hash rotates the key in place; removing the entry revokes the key.
 *
 * Unknown tenant slugs cause a fail-fast result so a typo can't silently
 * leave a partner BFF without its key at startup. Unknown scopes are
 * rejected for the same reason. The one exception is an entry marked
 * [Entry.awaitTenant]: it is held until a workspace with that slug is created
 * or imported, and [applyPendingFor] provisions it then, with no restart.
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
        /** The workspace may not exist yet; hold the entry until it is created instead of failing. */
        val awaitTenant: Boolean = false,
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

    enum class Action { CREATED, UPDATED, UNCHANGED, PENDING, REVOKED }

    private val pending = mutableListOf<Entry>()

    /** Entries waiting for their workspace to be created. */
    val pendingEntries: List<Entry>
        get() = synchronized(pending) { pending.toList() }

    fun ensureBootstrapped(entries: List<Entry>): Result {
        // Validate every entry before writing any, so a bad entry late in the list leaves no keys behind.
        for (entry in entries) {
            val invalidScope = entry.scopes.firstOrNull { it !in ApiScope.ALL }
            if (invalidScope != null) {
                return Result.Failure("Unknown scope '$invalidScope' for bootstrap key '${entry.name}'")
            }
            if (!entry.awaitTenant && !tenantRepository.existsBySlug(entry.tenantSlug)) {
                return Result.Failure(
                    "Unknown tenant slug '${entry.tenantSlug}' for bootstrap key '${entry.name}'. " +
                        "Set \"awaitTenant\": true if the workspace is created after startup.",
                )
            }
        }
        val outcomes = mutableListOf<Outcome>()
        synchronized(pending) {
            pending.clear()
            for (entry in entries) {
                val tenant = tenantRepository.findBySlug(entry.tenantSlug)
                if (tenant == null) {
                    pending += entry
                    outcomes += Outcome(entry.tenantSlug, entry.name, Action.PENDING)
                } else {
                    outcomes += apply(entry, tenant)
                }
            }
        }
        outcomes += revokeRemoved(entries)
        return Result.Provisioned(outcomes)
    }

    /**
     * Provisions the held entries for [tenant], right after it is created or imported, inside the
     * creating transaction. Entries stay held: applying one twice is a no-op, and a workspace that
     * is deleted and created again gets its keys back.
     */
    fun applyPendingFor(tenant: Tenant): List<Outcome> =
        synchronized(pending) {
            pending.filter { it.tenantSlug == tenant.slug }.map { apply(it, tenant) }
        }

    /**
     * The list is authoritative: a key provisioned from an entry that is no longer in it is
     * disabled and handed back to the console. Disabled rather than deleted, so its audit trail
     * stays, and restoring the entry enables it again.
     */
    private fun revokeRemoved(entries: List<Entry>): List<Outcome> {
        val listed = entries.map { it.tenantSlug to it.name }.toSet()
        return tenantRepository.findAll().flatMap { tenant ->
            apiKeyRepository
                .findByTenantId(tenant.id)
                .filter { it.bootstrapName != null && (tenant.slug to it.bootstrapName) !in listed }
                .map { key ->
                    apiKeyRepository.revoke(key.id!!, tenant.id, releaseBootstrap = true)
                    Outcome(tenant.slug, key.bootstrapName!!, Action.REVOKED)
                }
        }
    }

    private fun apply(
        entry: Entry,
        tenant: Tenant,
    ): Outcome {
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
            return Outcome(entry.tenantSlug, entry.name, Action.CREATED)
        }

        val hashUnchanged = existing.keyHash == entry.keyHash
        val scopesUnchanged = existing.scopes.toSet() == entry.scopes.toSet()
        val brandedAsBootstrap = existing.bootstrapName == entry.name
        val enabled = existing.enabled
        val dialectUnchanged = existing.scimDialect == resolvedDialect

        val entryIsUnchanged =
            hashUnchanged && scopesUnchanged && brandedAsBootstrap && enabled && dialectUnchanged
        if (entryIsUnchanged) {
            return Outcome(entry.tenantSlug, entry.name, Action.UNCHANGED)
        }

        apiKeyRepository.updateBootstrap(
            id = existing.id!!,
            keyHash = entry.keyHash,
            scopes = entry.scopes,
            bootstrapName = entry.name,
            scimDialect = resolvedDialect,
        )
        return Outcome(entry.tenantSlug, entry.name, Action.UPDATED)
    }
}
