package com.kauth.config

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
)

private val parser = Json { ignoreUnknownKeys = true }

fun parseBootstrapApiKeyEntries(json: String): List<ApiKeyBootstrapService.Entry> {
    val decoded = parser.decodeFromString<List<BootstrapEntryJson>>(json)
    return decoded.map { e ->
        ApiKeyBootstrapService.Entry(
            tenantSlug = e.tenant,
            name = e.name,
            scopes = e.scopes,
            keyHash = e.keyHash,
            keyPrefix = e.keyPrefix,
        )
    }
}
