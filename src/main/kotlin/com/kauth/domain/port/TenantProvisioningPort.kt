package com.kauth.domain.port

import com.kauth.domain.model.Tenant

/**
 * Port (outbound) — brings a newly created tenant to a usable state without a
 * server restart: an active signing key and the built-in clients wired to this
 * deployment's base URL.
 *
 * Implementations must be idempotent: a tenant that already has an active key
 * or a correctly configured built-in client is left as it is.
 */
fun interface TenantProvisioningPort {
    fun provision(tenant: Tenant)
}
