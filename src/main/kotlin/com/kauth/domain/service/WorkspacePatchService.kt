package com.kauth.domain.service

import com.kauth.domain.model.SmtpPatch
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.WorkspacePatch
import com.kauth.domain.model.WorkspaceSettingsUpdate
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TransactionRunner

/**
 * Applies a [WorkspacePatch] through the same services the console's settings pages use, so the
 * API and the console share validation and audit events. SMTP is written first, so a patch can
 * configure email delivery and turn on an email sign-in method in one request.
 *
 * With a [TransactionRunner], a failure in any step rolls back the steps before it.
 */
class WorkspacePatchService(
    private val tenantRepository: TenantRepository,
    private val workspaceSettingsService: WorkspaceSettingsService,
    private val accountService: AdminAccountService,
    private val availableLocales: () -> Set<String>,
    private val transactionRunner: TransactionRunner? = null,
) {
    fun patch(
        tenantId: TenantId,
        patch: WorkspacePatch,
    ): AdminResult<Tenant> {
        val block = {
            val result = apply(tenantId, patch)
            if (result is AdminResult.Failure) throw RollbackSignal(result.error)
            result
        }
        return try {
            transactionRunner?.runInTransaction(block) ?: block()
        } catch (signal: RollbackSignal) {
            AdminResult.Failure(signal.error)
        }
    }

    private fun apply(
        tenantId: TenantId,
        patch: WorkspacePatch,
    ): AdminResult<Tenant> {
        var tenant =
            tenantRepository.findById(tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace not found."))

        val locale = patch.defaultLocale?.trim()?.lowercase()
        if (!locale.isNullOrEmpty() && locale !in availableLocales()) {
            val known = availableLocales().sorted().joinToString(", ")
            return AdminResult.Failure(AdminError.Validation("Unknown locale '$locale'. Available: $known."))
        }

        patch.smtp?.let { smtp ->
            tenant =
                when (val result = updateSmtp(tenant, smtp)) {
                    is AdminResult.Success -> result.value
                    is AdminResult.Failure -> return result
                }
        }

        if (patch.touchesSettings) {
            val update = patch.applyTo(WorkspaceSettingsUpdate.from(tenant))
            tenant =
                when (val result = workspaceSettingsService.updateWorkspaceSettings(tenant.slug, update)) {
                    is AdminResult.Success -> result.value
                    is AdminResult.Failure -> return result
                }
        }

        if (locale != null) {
            val theme = tenant.theme.copy(defaultLocale = locale.ifEmpty { null })
            when (val result = workspaceSettingsService.updateTheme(tenant.slug, theme)) {
                is AdminResult.Success -> tenant = tenant.copy(theme = result.value)
                is AdminResult.Failure -> return AdminResult.Failure(result.error)
            }
        }

        return AdminResult.Success(tenant)
    }

    private fun updateSmtp(
        tenant: Tenant,
        smtp: SmtpPatch,
    ): AdminResult<Tenant> =
        accountService.updateSmtpConfig(
            slug = tenant.slug,
            smtpHost = smtp.host ?: tenant.smtpHost,
            smtpPort = smtp.port ?: tenant.smtpPort,
            smtpUsername = smtp.username ?: tenant.smtpUsername,
            smtpPassword = smtp.password,
            smtpFromAddress = smtp.fromAddress ?: tenant.smtpFromAddress,
            smtpFromName = smtp.fromName ?: tenant.smtpFromName,
            smtpTlsEnabled = smtp.tlsEnabled ?: tenant.smtpTlsEnabled,
            smtpEnabled = smtp.enabled ?: tenant.smtpEnabled,
        )

    private class RollbackSignal(
        val error: AdminError,
    ) : RuntimeException(error.message, null, false, false)
}
