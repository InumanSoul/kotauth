package com.kauth.infrastructure

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.TenantProvisioningPort
import com.kauth.domain.service.ApiKeyBootstrapService
import org.slf4j.LoggerFactory

/** The steps every newly created or imported tenant needs before it can issue tokens. */
class TenantProvisioner(
    private val keyProvisioning: KeyProvisioningService,
    // Nullable only for route tests that wire no portal client provisioning.
    private val portalClientProvisioning: PortalClientProvisioning?,
    // Applies KAUTH_BOOTSTRAP_API_KEYS entries that were waiting for this workspace.
    private val apiKeyBootstrap: ApiKeyBootstrapService? = null,
) : TenantProvisioningPort {
    private val log = LoggerFactory.getLogger(TenantProvisioner::class.java)

    override fun provision(tenant: Tenant) {
        keyProvisioning.provisionForTenant(tenant)
        portalClientProvisioning?.provisionForTenant(tenant)
        apiKeyBootstrap?.applyPendingFor(tenant)?.forEach { o ->
            log.info("Bootstrap API key '{}' for tenant '{}': {}", o.name, o.tenantSlug, o.action.name.lowercase())
        }
    }
}
