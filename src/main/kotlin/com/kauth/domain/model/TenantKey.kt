package com.kauth.domain.model

/**
 * An RSA signing key pair belonging to a tenant.
 *
 * Each tenant acts as its own Authorization Server and signs its JWTs with
 * its own private key. Clients verify tokens using the matching public key,
 * published at the tenant's JWKS endpoint.
 *
 * [keyId] maps to the JWT "kid" header claim so clients know which key to
 * use when verifying a token — necessary during key rotation when multiple
 * active keys coexist.
 *
 * Private keys are stored as PEM strings. At-rest encryption is a platform
 * concern (database TDE / KMS-backed storage) outside this model.
 */
data class TenantKey(
    val id: Int? = null,
    val tenantId: TenantId,
    val keyId: String,
    val algorithm: String = "RS256",
    val publicKeyPem: String,
    val privateKeyPem: String,
    val enabled: Boolean = true,
    /** True if this is the current signing key. Exactly one key per tenant should be active. */
    val active: Boolean = false,
)
