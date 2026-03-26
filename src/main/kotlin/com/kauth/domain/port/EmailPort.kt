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
        tenant: Tenant,
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
        tenant: Tenant,
    )

    /**
     * Notifies a user that their account has been locked after repeated failed sign-in attempts.
     * Includes a password reset link ([resetUrl]) so the user can regain access immediately.
     * [lockoutDuration] is a human-readable string such as "15 minutes" or "1 hour".
     */
    fun sendAccountLockedEmail(
        to: String,
        toName: String,
        resetUrl: String,
        workspaceName: String,
        lockoutDuration: String,
        tenant: Tenant,
    )

    /**
     * Notifies a user that their password was successfully changed.
     * Contains no action link — intentional to avoid a phishing surface.
     */
    fun sendPasswordChangedEmail(
        to: String,
        toName: String,
        workspaceName: String,
        tenant: Tenant,
    )
}
