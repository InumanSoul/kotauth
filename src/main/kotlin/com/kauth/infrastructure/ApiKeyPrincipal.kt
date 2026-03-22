package com.kauth.infrastructure

import com.kauth.domain.model.ApiKey
import io.ktor.server.auth.*

/**
 * Ktor authentication principal for API key requests.
 *
 * Set by the bearer auth provider after verifying the token starts with "kauth_".
 * Routes call [ApiKeyService.validate()] with tenant context to perform the full
 * DB-backed check and populate [resolvedKey].
 */
data class ApiKeyPrincipal(
    /** The raw Bearer token extracted from the Authorization header. */
    val rawToken: String,
    /** Populated by the route after successful tenant-scoped validation. */
    val resolvedKey: ApiKey? = null,
) : Principal
