package com.kauth.config

import com.kauth.adapter.web.scim.scimDialects
import com.kauth.domain.service.ApiKeyBootstrapService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class BootstrapEntryJson(
    val tenant: String,
    val name: String,
    val scopes: List<String>,
    val keyHash: String,
    val keyPrefix: String? = null,
    val scimDialect: String? = null,
)

private val parser = Json { ignoreUnknownKeys = true }

/**
 * Decodes `KAUTH_BOOTSTRAP_API_KEYS` into bootstrap entries.
 *
 * `scimDialect` is optional and defaults to `rfc`, the spec-canonical pass-through. It is checked
 * against the registered set here, at startup, rather than left to the request path: an
 * unrecognised id resolves to the pass-through when a provisioning request arrives, so a typo
 * would otherwise boot cleanly and surface later as a connector whose payloads are parsed by a
 * dialect the operator did not choose. A failure here is fatal, the same as any other malformed
 * entry in this variable.
 */
fun parseBootstrapApiKeyEntries(json: String): List<ApiKeyBootstrapService.Entry> {
    val decoded = parser.decodeFromString<List<BootstrapEntryJson>>(json)
    return decoded.map { e ->
        ApiKeyBootstrapService.Entry(
            tenantSlug = e.tenant,
            name = e.name,
            scopes = e.scopes,
            keyHash = e.keyHash,
            keyPrefix = e.keyPrefix,
            scimDialect = e.scimDialect?.let { requireRegisteredDialect(it, e.name) },
        )
    }
}

private fun requireRegisteredDialect(
    id: String,
    keyName: String,
): String {
    require(scimDialects.any { it.id == id }) {
        "Unknown scimDialect '$id' for bootstrap key '$keyName'; registered dialects are " +
            scimDialects.joinToString(", ") { it.id }
    }
    return id
}
