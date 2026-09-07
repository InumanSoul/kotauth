package com.kauth.infrastructure

import com.kauth.domain.model.ApiKey
import io.ktor.server.auth.*

/**
 * Ktor authentication principal for API key requests.
 *
 * Set by the bearer auth provider after verifying the token starts with "kauth_".
 */
data class ApiKeyPrincipal(
    /** The raw Bearer token extracted from the Authorization header. */
    val rawToken: String,
    /**
     * Vestigial: nothing populates this. The principal is built before tenant resolution and Ktor
     * principals are immutable, so the resolved key is stamped onto the call as `ApiKeyAttr`
     * instead. Read that, not this — a derived accessor here would answer for every key with
     * whatever a null resolves to.
     */
    val resolvedKey: ApiKey? = null,
) : Principal
