package com.kauth.infrastructure

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.TenantProvisioningPort

/** The steps every newly created or imported tenant needs before it can issue tokens. */
class TenantProvisioner(
    private val keyProvisioning: KeyProvisioningService,
    // Nullable only for route tests that wire no portal client provisioning.
    private val portalClientProvisioning: PortalClientProvisioning?,
) : TenantProvisioningPort {
    override fun provision(tenant: Tenant) {
        keyProvisioning.provisionForTenant(tenant)
        portalClientProvisioning?.provisionForTenant(tenant)
    }
}
