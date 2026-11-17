package com.kauth.domain.service

import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.port.TenantProvisioningPort
import com.kauth.domain.port.TransactionRunner

/**
 * Creates a workspace that is usable the moment the call returns: an active signing key, the
 * built-in portal client and the default `admin` and `user` roles. The console and the master
 * admin API both create workspaces through here, so the two cannot drift.
 *
 * All writes share one transaction when a [TransactionRunner] is supplied: a failure in any step
 * leaves no half-created workspace behind.
 */
class WorkspaceCreationService(
    private val workspaceSettingsService: WorkspaceSettingsService,
    private val roleGroupService: RoleGroupService,
    private val tenantProvisioning: TenantProvisioningPort,
    private val transactionRunner: TransactionRunner? = null,
) {
    fun create(
        slug: String,
        displayName: String,
        issuerUrl: String?,
        registrationEnabled: Boolean = true,
        emailVerificationRequired: Boolean = false,
    ): AdminResult<Tenant> {
        val block = {
            val created =
                workspaceSettingsService.createWorkspace(
                    slug = slug,
                    displayName = displayName,
                    issuerUrl = issuerUrl,
                    registrationEnabled = registrationEnabled,
                    emailVerificationRequired = emailVerificationRequired,
                )
            if (created is AdminResult.Success) provisionDefaults(created.value)
            created
        }
        return transactionRunner?.runInTransaction(block) ?: block()
    }

    private fun provisionDefaults(tenant: Tenant) {
        tenantProvisioning.provision(tenant)
        for ((name, description) in DEFAULT_ROLES) {
            val role = roleGroupService.createRole(tenant.id, name, description, RoleScope.TENANT, null)
            // A fresh workspace has no roles, so a failure here is a defect; throwing rolls the creation back.
            check(role is AdminResult.Success) {
                "Default role '$name' could not be created: ${(role as AdminResult.Failure).error.message}"
            }
        }
    }

    private companion object {
        val DEFAULT_ROLES =
            listOf(
                "admin" to "Full administrative access within this workspace",
                "user" to "Standard authenticated user. The default role for self-registrations",
            )
    }
}
