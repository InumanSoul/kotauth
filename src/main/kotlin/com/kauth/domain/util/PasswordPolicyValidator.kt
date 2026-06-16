package com.kauth.domain.util

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.PasswordPolicyPort
import com.kauth.domain.service.SelfServiceError

fun validatePasswordPolicy(
    newPassword: String,
    tenant: Tenant,
    passwordPolicy: PasswordPolicyPort?,
    userId: UserId? = null,
    tenantId: TenantId? = null,
    checkHistory: Boolean = false,
): SelfServiceError.Validation? {
    val policyError = passwordPolicy?.validate(newPassword, tenant)
    if (policyError != null) return SelfServiceError.Validation(policyError)
    if (passwordPolicy == null && newPassword.length < tenant.passwordPolicyMinLength) {
        return SelfServiceError.Validation(
            "Password must be at least ${tenant.passwordPolicyMinLength} characters.",
        )
    }
    val needsHistoryCheck =
        checkHistory &&
            passwordPolicy != null &&
            tenant.passwordPolicyHistoryCount > 0 &&
            userId != null &&
            tenantId != null
    if (needsHistoryCheck &&
        passwordPolicy!!.isInHistory(userId!!, tenantId!!, newPassword, tenant.passwordPolicyHistoryCount)
    ) {
        return SelfServiceError.Validation(
            "This password has been used recently. Please choose a different password.",
        )
    }
    return null
}
