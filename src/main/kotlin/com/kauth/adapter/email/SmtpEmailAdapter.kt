package com.kauth.adapter.email

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.EmailPort
import org.slf4j.LoggerFactory
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Email adapter — delivers transactional emails via SMTP (JavaMail).
 *
 * Each send operation reads SMTP config from the [Tenant] domain object at call time,
 * so config changes take effect without restart.
 *
 * TLS connection modes:
 *   Port 465 → SMTPS (SSL-first):  mail.smtp.ssl.enable=true
 *   Port 587+ → STARTTLS:          mail.smtp.starttls.enable=true + starttls.required=true
 * These two modes are mutually exclusive. Conflating them is the most common JavaMail mistake.
 *
 * Emails use plain HTML with no template engine. Each email applies TenantTheme branding
 * (accent color, font family, border radius, logo) via a shared [buildEmailHtml] layout function.
 *
 * The caller ([UserSelfServiceService]) is responsible for checking [Tenant.isSmtpReady]
 * before calling this adapter. If SMTP is not configured, this adapter will throw.
 */
class SmtpEmailAdapter : EmailPort {
    private val log = LoggerFactory.getLogger(SmtpEmailAdapter::class.java)

    override fun sendVerificationEmail(
        to: String,
        toName: String,
        verifyUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val subject = "Verify your email address — $workspaceName"
        val html = buildVerificationHtml(toName, verifyUrl, tenant)
        val text = buildVerificationText(toName, verifyUrl, workspaceName)
        send(to, toName, subject, html, text, tenant)
    }

    override fun sendPasswordResetEmail(
        to: String,
        toName: String,
        resetUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val subject = "Reset your password — $workspaceName"
        val html = buildPasswordResetHtml(toName, resetUrl, tenant)
        val text = buildPasswordResetText(toName, resetUrl, workspaceName)
        send(to, toName, subject, html, text, tenant)
    }

    override fun sendAccountLockedEmail(
        to: String,
        toName: String,
        resetUrl: String,
        workspaceName: String,
        lockoutDuration: String,
        tenant: Tenant,
    ) {
        val subject = "Your account has been locked — $workspaceName"
        val html = buildAccountLockedHtml(toName, resetUrl, lockoutDuration, tenant)
        val text = buildAccountLockedText(toName, resetUrl, workspaceName, lockoutDuration)
        send(to, toName, subject, html, text, tenant)
    }

    override fun sendPasswordChangedEmail(
        to: String,
        toName: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val subject = "Your password has been changed — $workspaceName"
        val html = buildPasswordChangedHtml(toName, workspaceName, tenant)
        val text = buildPasswordChangedText(toName, workspaceName, tenant)
        send(to, toName, subject, html, text, tenant)
    }

    override fun sendTestEmail(
        to: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val subject = "KotAuth SMTP Test — $workspaceName"
        val html =
            buildEmailHtml(
                tenant = tenant,
                heading = "SMTP Configuration Test",
                bodyHtml =
                    "This email confirms that SMTP is correctly configured for " +
                        "<strong>${htmlEscape(workspaceName)}</strong>. " +
                        "Email delivery (verification, password reset, notifications) is operational.",
                footerHtml = "Sent by KotAuth to verify SMTP configuration.",
            )
        val text = "SMTP test email for $workspaceName. Email delivery is operational."
        send(to, to, subject, html, text, tenant)
    }

    override fun sendInviteEmail(
        to: String,
        toName: String,
        inviteUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val subject = "You've been invited to join $workspaceName"
        val html = buildInviteHtml(toName, inviteUrl, tenant)
        val text = buildInviteText(toName, inviteUrl, workspaceName)
        send(to, toName, subject, html, text, tenant)
    }

    private fun send(
        to: String,
        toName: String,
        subject: String,
        html: String,
        text: String,
        tenant: Tenant,
    ) {
        val host = tenant.smtpHost ?: error("SMTP host not configured")
        val port = tenant.smtpPort
        // Port 465 always uses SSL-first (SMTPS). Any other port uses STARTTLS when TLS is enabled.
        val useSsl = (port == 465)

        // Auth requires BOTH username and password — if either is missing, skip auth entirely.
        val hasAuth = !tenant.smtpUsername.isNullOrBlank() && !tenant.smtpPassword.isNullOrBlank()

        val props =
            Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.auth", if (hasAuth) "true" else "false")

                when {
                    useSsl -> {
                        // SSL-first connection (port 465 / SMTPS) — JavaMail wraps the socket in TLS
                        // immediately on connect. Do NOT mix with starttls.enable.
                        put("mail.smtp.ssl.enable", "true")
                        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                    }
                    tenant.smtpTlsEnabled -> {
                        // STARTTLS — connect plaintext then upgrade. `required=true` prevents silent
                        // downgrade to unencrypted when the server is misconfigured.
                        put("mail.smtp.starttls.enable", "true")
                        put("mail.smtp.starttls.required", "true")
                        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                    }
                }

                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "10000")
                put("mail.smtp.writetimeout", "10000")
            }

        val authenticator: Authenticator? =
            if (hasAuth) {
                object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(tenant.smtpUsername, tenant.smtpPassword)
                }
            } else {
                null
            }

        log.debug(
            "SMTP send: to={} host={}:{} ssl={} starttls={} auth={}",
            to,
            host,
            port,
            useSsl,
            tenant.smtpTlsEnabled && !useSsl,
            hasAuth,
        )

        val session = Session.getInstance(props, authenticator)

        val fromAddress =
            InternetAddress(
                tenant.smtpFromAddress ?: error("SMTP from address not configured"),
                tenant.smtpFromName ?: workspaceDisplayName(tenant),
            )

        val message =
            MimeMessage(session).apply {
                setFrom(fromAddress)
                setRecipient(Message.RecipientType.TO, InternetAddress(to, toName))
                setSubject(subject, "UTF-8")

                val htmlPart = MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") }
                val textPart = MimeBodyPart().apply { setContent(text, "text/plain; charset=UTF-8") }

                // multipart/alternative: mail clients pick the best format they support
                val multipart =
                    MimeMultipart("alternative").apply {
                        addBodyPart(textPart) // plain text first (fallback)
                        addBodyPart(htmlPart) // HTML second (preferred)
                    }
                setContent(multipart)
            }

        try {
            Transport.send(message)
            log.info("SMTP send OK: to={} subject='{}'", to, subject)
        } catch (e: Exception) {
            log.warn(
                "SMTP send FAILED: to={} host={}:{} ssl={} reason={}",
                to,
                host,
                port,
                useSsl,
                e.message,
                e,
            )
            throw e
        }
    }

    private fun workspaceDisplayName(tenant: Tenant) = tenant.displayName

    // -------------------------------------------------------------------------
    // Shared layout builders
    // -------------------------------------------------------------------------

    /**
     * Renders the full HTML email with the shared table-based shell and TenantTheme branding.
     *
     * Email backgrounds are always light (#f4f4f5 / #ffffff) regardless of the tenant's auth-page
     * theme — dark-mode email rendering is inconsistent across clients and inbox providers.
     *
     * When [ctaLabel] and [ctaUrl] are both non-null the CTA button and URL-fallback footer are
     * rendered. When [ctaLabel] is null the button section is omitted entirely (e.g. password-
     * changed notification, which has no actionable link).
     */
    private fun buildEmailHtml(
        tenant: Tenant,
        heading: String,
        bodyHtml: String,
        ctaLabel: String? = null,
        ctaUrl: String? = null,
        footerHtml: String,
    ): String {
        val theme = tenant.theme
        val workspace = htmlEscape(tenant.displayName)
        val font = "${htmlEscape(theme.fontFamily)}, sans-serif"

        val logoSection =
            if (theme.logoUrl != null) {
                """<img src="${htmlEscape(theme.logoUrl)}" alt="$workspace" border="0" """ +
                    """style="max-height:40px;max-width:200px;margin:0 0 16px 0;display:block;">"""
            } else {
                ""
            }

        val safeCtaUrl = ctaUrl?.let { htmlEscape(it) }
        val safeCtaLabel = ctaLabel?.let { htmlEscape(it) }
        val radius = htmlEscape(theme.borderRadius)

        val ctaSection =
            if (safeCtaLabel != null && safeCtaUrl != null) {
                """
                <a href="$safeCtaUrl" style="display:inline-block;padding:12px 24px;background:${theme.accentColor};color:${theme.accentForeground};border-radius:$radius;text-decoration:none;font-size:14px;font-weight:600;">
                  $safeCtaLabel
                </a>
                <p style="font-size:12px;color:#71717a;margin:24px 0 0 0;line-height:1.5;">
                  $footerHtml<br>
                  If the button doesn't work, copy this link:<br>
                  <a href="$safeCtaUrl" style="color:#71717a;word-break:break-all;">$safeCtaUrl</a>
                </p>
                """.trimIndent()
            } else {
                """
                <p style="font-size:12px;color:#71717a;margin:24px 0 0 0;line-height:1.5;">
                  $footerHtml
                </p>
                """.trimIndent()
            }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;padding:0;font-family:$font;background:#f4f4f5;color:#18181b;">
              <table width="100%" cellpadding="0" cellspacing="0">
                <tr><td align="center" style="padding:40px 16px;">
                  <table width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background:#ffffff;border-radius:$radius;padding:40px;border:1px solid #e4e4e7;">
                    <tr><td>
                      $logoSection
                      <p style="font-size:13px;color:#71717a;margin:0 0 8px 0;text-transform:uppercase;letter-spacing:.05em;">$workspace</p>
                      <h1 style="font-size:22px;margin:0 0 16px 0;color:#09090b;">$heading</h1>
                      <p style="font-size:15px;line-height:1.6;color:#3f3f46;margin:0 0 24px 0;">
                        $bodyHtml
                      </p>
                      $ctaSection
                    </td></tr>
                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """.trimIndent()
    }

    /**
     * Renders the plain-text fallback for all transactional emails.
     *
     * [url] is omitted when null — callers that have no CTA (e.g. password-changed) pass null.
     */
    private fun buildEmailText(
        workspace: String,
        heading: String,
        body: String,
        url: String? = null,
        footer: String,
    ): String {
        val urlSection = if (url != null) "\n$url\n" else ""
        return "$workspace — $heading\n\n$body\n$urlSection\n$footer"
    }

    // -------------------------------------------------------------------------
    // Email templates — thin wrappers over the shared layout builders
    // -------------------------------------------------------------------------

    private fun buildVerificationHtml(
        name: String,
        url: String,
        tenant: Tenant,
    ) = buildEmailHtml(
        tenant = tenant,
        heading = "Verify your email address",
        bodyHtml = "Hi ${htmlEscape(
            name,
        )},<br><br>Click the button below to verify your email address. This link expires in 24 hours.",
        ctaLabel = "Verify email address",
        ctaUrl = url,
        footerHtml = "If you did not create an account, you can safely ignore this email.",
    )

    private fun buildVerificationText(
        name: String,
        url: String,
        workspace: String,
    ) = buildEmailText(
        workspace = workspace,
        heading = "Verify your email address",
        body = "Hi $name,\n\nClick the link below to verify your email address. This link expires in 24 hours.",
        url = url,
        footer = "If you did not create an account, you can safely ignore this email.",
    )

    private fun buildPasswordResetHtml(
        name: String,
        url: String,
        tenant: Tenant,
    ) = buildEmailHtml(
        tenant = tenant,
        heading = "Reset your password",
        bodyHtml =
            "Hi ${htmlEscape(name)},<br><br>" +
                "We received a request to reset your password. " +
                "Click the button below to choose a new one. This link expires in 1 hour.",
        ctaLabel = "Reset password",
        ctaUrl = url,
        footerHtml = "If you did not request a password reset, you can safely ignore this email.",
    )

    private fun buildPasswordResetText(
        name: String,
        url: String,
        workspace: String,
    ) = buildEmailText(
        workspace = workspace,
        heading = "Reset your password",
        body =
            "Hi $name,\n\nWe received a request to reset your password. " +
                "Click the link below to choose a new one. This link expires in 1 hour.",
        url = url,
        footer = "If you did not request a password reset, you can safely ignore this email.",
    )

    private fun buildAccountLockedHtml(
        name: String,
        url: String,
        lockoutDuration: String,
        tenant: Tenant,
    ) = buildEmailHtml(
        tenant = tenant,
        heading = "Your account has been locked",
        bodyHtml =
            "Hi ${htmlEscape(name)},<br><br>" +
                "We temporarily locked your ${htmlEscape(
                    tenant.displayName,
                )} account after several failed sign-in attempts. " +
                "Your account will automatically unlock in $lockoutDuration. " +
                "If you'd like to regain access sooner, or if you don't recognize this activity, you can reset your password now.",
        ctaLabel = "Reset password",
        ctaUrl = url,
        footerHtml =
            "If you made these sign-in attempts, you can safely ignore this email " +
                "— your account will unlock automatically.",
    )

    private fun buildAccountLockedText(
        name: String,
        url: String,
        workspace: String,
        lockoutDuration: String,
    ) = buildEmailText(
        workspace = workspace,
        heading = "Your account has been locked",
        body =
            "Hi $name,\n\nWe temporarily locked your $workspace account after several failed sign-in attempts.\n" +
                "Your account will automatically unlock in $lockoutDuration.\n" +
                "If you'd like to regain access sooner, or if you don't recognize this activity, you can reset your password now.",
        url = url,
        footer =
            "If you made these sign-in attempts, you can safely ignore this email " +
                "— your account will unlock automatically.",
    )

    private fun buildPasswordChangedHtml(
        name: String,
        workspace: String,
        tenant: Tenant,
    ): String {
        val loginUrl = tenant.issuerUrl?.replace("/t/${tenant.slug}", "/t/${tenant.slug}/account/login") ?: ""
        val loginLink =
            if (loginUrl.isNotBlank()) {
                " Sign in at <a href=\"${htmlEscape(loginUrl)}\" " +
                    "style=\"color:#71717a;\">${htmlEscape(loginUrl)}</a>" +
                    " and use the Forgot password link."
            } else {
                ""
            }
        return buildEmailHtml(
            tenant = tenant,
            heading = "Your password has been changed",
            bodyHtml =
                "Hi ${htmlEscape(name)},<br><br>" +
                    "Your ${htmlEscape(workspace)} password was successfully changed. " +
                    "If you made this change, no action is needed. " +
                    "If you did not make this change, reset your password immediately." +
                    loginLink,
            ctaLabel = null,
            ctaUrl = null,
            footerHtml =
                "For security, all active sessions were signed out " +
                    "when your password was changed.",
        )
    }

    private fun buildPasswordChangedText(
        name: String,
        workspace: String,
        tenant: Tenant,
    ): String {
        val loginUrl = tenant.issuerUrl?.replace("/t/${tenant.slug}", "/t/${tenant.slug}/account/login") ?: ""
        val loginHint = if (loginUrl.isNotBlank()) "\nSign in at $loginUrl and use the Forgot password link." else ""
        return buildEmailText(
            workspace = workspace,
            heading = "Your password has been changed",
            body =
                "Hi $name,\n\nYour $workspace password was successfully changed.\n" +
                    "If you made this change, no action is needed.\n" +
                    "If you did not make this change, reset your password immediately." +
                    loginHint,
            url = null,
            footer =
                "For security, all active sessions were signed out " +
                    "when your password was changed.",
        )
    }

    private fun buildInviteHtml(
        name: String,
        url: String,
        tenant: Tenant,
    ) = buildEmailHtml(
        tenant = tenant,
        heading = "You\u2019ve been invited",
        bodyHtml =
            "Hi ${htmlEscape(name)},<br><br>" +
                "You\u2019ve been added to <strong>${htmlEscape(tenant.displayName)}</strong>. " +
                "Click the button below to set your password and activate your account. " +
                "This link expires in 72 hours.",
        ctaLabel = "Set your password",
        ctaUrl = url,
        footerHtml = "If you weren\u2019t expecting this, you can safely ignore this email. " +
            "No account will be activated without clicking the link above.",
    )

    private fun buildInviteText(
        name: String,
        url: String,
        workspace: String,
    ) = buildEmailText(
        workspace = workspace,
        heading = "You've been invited",
        body =
            "Hi $name,\n\nYou've been added to $workspace. " +
                "Click the link below to set your password and activate your account. " +
                "This link expires in 72 hours.",
        url = url,
        footer = "If you weren't expecting this, you can safely ignore this email.",
    )

    private fun htmlEscape(s: String) =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
