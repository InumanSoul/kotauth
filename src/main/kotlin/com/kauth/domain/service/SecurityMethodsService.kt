package com.kauth.domain.service

import com.kauth.domain.model.AuthMethodRow
import com.kauth.domain.model.MethodKey
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.Requirement
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.TenantRepository

// WorkspaceSettingsService is intentionally NOT injected: updateSecurityMethods writes via
// tenantRepository directly to avoid duplicating audit-log and CORS-invalidation side effects
// that WorkspaceSettingsService.applyWorkspaceSettings owns for the general settings flow.
class SecurityMethodsService(
    private val tenantRepository: TenantRepository,
    private val identityProviderRepository: IdentityProviderRepository,
) {
    fun list(tenant: Tenant): List<AuthMethodRow> {
        val rows = mutableListOf<AuthMethodRow>()
        val smtpBlocking = !tenant.isSmtpReady

        rows +=
            AuthMethodRow(
                key = MethodKey.PASSWORD,
                labelKey = "AUTH_METHOD_PASSWORD_LABEL",
                descriptionKey = null,
                enabled = tenant.securityConfig.passwordLoginEnabled,
                requirements = emptyList(),
                toggleable = true,
            )

        rows +=
            AuthMethodRow(
                key = MethodKey.PASSKEY,
                labelKey = "AUTH_METHOD_PASSKEY_LABEL",
                descriptionKey = null,
                enabled = tenant.passkeysEnabled,
                requirements = emptyList(),
                toggleable = true,
            )

        rows +=
            AuthMethodRow(
                key = MethodKey.MAGIC_LINK,
                labelKey = "AUTH_METHOD_MAGIC_LINK_LABEL",
                descriptionKey = "AUTH_METHOD_MAGIC_LINK_DESC",
                enabled = tenant.securityConfig.magicLinkEnabled,
                requirements = listOf(Requirement.SmtpRequired),
                toggleable = tenant.securityConfig.magicLinkEnabled || !smtpBlocking,
            )

        rows +=
            AuthMethodRow(
                key = MethodKey.EMAIL_OTP,
                labelKey = "AUTH_METHOD_EMAIL_OTP_LABEL",
                descriptionKey = "AUTH_METHOD_EMAIL_OTP_DESC",
                enabled = tenant.securityConfig.emailOtpLoginEnabled,
                requirements = listOf(Requirement.SmtpRequired),
                toggleable = tenant.securityConfig.emailOtpLoginEnabled || !smtpBlocking,
            )

        val allIdps = identityProviderRepository.findAllByTenant(tenant.id)
        val enabledProviders = allIdps.filter { it.enabled }.map { it.provider }.toSet()

        // Only emit rows for providers that have credentials configured.
        // Unconfigured providers are noise for the operator; they can add them via Identity Providers.
        ProviderKey.RESERVED.forEach { providerKey ->
            val hasCredentials = allIdps.any { it.provider == providerKey }
            if (!hasCredentials) return@forEach

            // A provider key is open, so the compiler cannot prove this covers every case;
            // RESERVED holds exactly the two keys that have a MethodKey row.
            val key =
                when (providerKey) {
                    ProviderKey.GOOGLE -> MethodKey.SOCIAL_GOOGLE
                    ProviderKey.GITHUB -> MethodKey.SOCIAL_GITHUB
                    else -> return@forEach
                }
            rows +=
                AuthMethodRow(
                    key = key,
                    labelKey = "AUTH_METHOD_${key.name}_LABEL",
                    descriptionKey = null,
                    enabled = providerKey in enabledProviders,
                    requirements = emptyList(),
                    toggleable = true,
                )
        }

        // One row for everything else. The count is what an operator needs from this page; the
        // switches live where the providers are configured.
        val brokered = allIdps.filter { it.provider !in ProviderKey.RESERVED }
        if (brokered.isNotEmpty()) {
            rows +=
                AuthMethodRow(
                    key = MethodKey.EXTERNAL_IDP,
                    labelKey = "AUTH_METHOD_EXTERNAL_IDP_LABEL",
                    descriptionKey = "AUTH_METHOD_EXTERNAL_IDP_DESC",
                    enabled = brokered.any { it.enabled },
                    requirements = emptyList(),
                    toggleable = false,
                    aggregateCount = brokered.size,
                )
        }

        return rows
    }

    fun updateSecurityMethods(
        tenantId: TenantId,
        requested: Map<MethodKey, Boolean>,
    ): AdminResult<Tenant> {
        val tenant =
            tenantRepository.findById(tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("Tenant $tenantId not found."))

        if (requested.values.none { it }) {
            return AdminResult.Failure(AdminError.NoMethodsEnabled)
        }

        val passwordEnabled = requested[MethodKey.PASSWORD] ?: tenant.securityConfig.passwordLoginEnabled
        if (!passwordEnabled && !tenant.isSmtpReady) {
            return AdminResult.Failure(AdminError.SmtpRequired)
        }

        val updated =
            tenant.copy(
                securityConfig =
                    tenant.securityConfig.copy(
                        passwordLoginEnabled =
                            requested[MethodKey.PASSWORD] ?: tenant.securityConfig.passwordLoginEnabled,
                        magicLinkEnabled = requested[MethodKey.MAGIC_LINK] ?: tenant.securityConfig.magicLinkEnabled,
                        emailOtpLoginEnabled =
                            requested[MethodKey.EMAIL_OTP] ?: tenant.securityConfig.emailOtpLoginEnabled,
                    ),
                passkeysEnabled = requested[MethodKey.PASSKEY] ?: tenant.passkeysEnabled,
            )

        tenantRepository.update(updated)
        return AdminResult.Success(updated)
    }
}
