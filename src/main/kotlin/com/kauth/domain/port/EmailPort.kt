package com.kauth.domain.port

import com.kauth.domain.model.Tenant

/**
 * Output port — transactional email delivery.
 *
 * Implemented by [SmtpEmailAdapter]. The domain service only calls this port
 * after confirming [Tenant.isSmtpReady] — the adapter itself does not check.
 *
 * Failures (e.g. SMTP unreachable) are thrown as exceptions and caught by the
 * calling service, which returns a [SelfServiceError.EmailDeliveryFailed] result.
 * Email failures must never block the auth hot path (login/register).
 */
interface EmailPort {

    /**
     * Sends an email verification link to the user's address.
     * [verifyUrl] contains the full URL with the raw token as a query parameter.
     */
    fun sendVerificationEmail(
        to: String,
        toName: String,
        verifyUrl: String,
        workspaceName: String,
        tenant: Tenant
    )

    /**
     * Sends a password reset link to the user's address.
     * [resetUrl] contains the full URL with the raw token as a query parameter.
     */
    fun sendPasswordResetEmail(
        to: String,
        toName: String,
        resetUrl: String,
        workspaceName: String,
        tenant: Tenant
    )
}
