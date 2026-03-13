package com.kauth.infrastructure

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantKey
import com.kauth.domain.port.TenantKeyRepository
import com.kauth.domain.port.TenantRepository
import org.slf4j.LoggerFactory

/**
 * Ensures every tenant has an active RSA signing key.
 *
 * Called once at startup (after DB migrations). For each tenant that has no
 * active key, a new RS256 key pair is generated and persisted.
 *
 * Key ID format: "{tenantSlug}-{timestamp}" — human-readable, unique, traceable.
 *
 * This is a one-time provisioning step. Key rotation in Phase 3 will be a
 * deliberate admin action (not automatic startup behaviour).
 */
class KeyProvisioningService(
    private val tenantRepository: TenantRepository,
    private val tenantKeyRepository: TenantKeyRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Provisions an RSA key pair for any tenant that doesn't already have one.
     * Safe to call on every startup — idempotent (skips tenants that already have keys).
     */
    fun provisionMissingKeys() {
        val tenants = tenantRepository.findAll()
        log.info("Key provisioning: checking ${tenants.size} tenant(s)")
        for (tenant in tenants) provisionForTenant(tenant)
    }

    /**
     * Provisions an RSA key pair for a single tenant immediately.
     * Called after admin console workspace creation so the tenant can issue
     * tokens without waiting for the next server restart.
     *
     * Idempotent — no-ops if the tenant already has an active key.
     */
    fun provisionForTenant(tenant: Tenant) {
        val existingKey = tenantKeyRepository.findActiveKey(tenant.id)
        if (existingKey == null) {
            log.info("Generating RS256 key pair for tenant '${tenant.slug}' (id=${tenant.id})")
            val keyPair = KeyGenerator.generateRsaKeyPair(
                keyId = "${tenant.slug}-${System.currentTimeMillis()}"
            )
            tenantKeyRepository.save(TenantKey(
                tenantId      = tenant.id,
                keyId         = keyPair.keyId,
                publicKeyPem  = keyPair.publicKeyPem,
                privateKeyPem = keyPair.privateKeyPem
            ))
            log.info("RS256 key '${keyPair.keyId}' created for tenant '${tenant.slug}'")
        } else {
            log.debug("Tenant '${tenant.slug}' already has active key '${existingKey.keyId}' — skipping")
        }
    }
}
