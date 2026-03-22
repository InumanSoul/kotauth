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
 * Emails use plain HTML with no template engine — workspace name only, no theme colors.
 * This is a deliberate KISS choice to keep the implementation simple.
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
        val html = buildVerificationHtml(toName, verifyUrl, workspaceName)
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
        val html = buildPasswordResetHtml(toName, resetUrl, workspaceName)
        val text = buildPasswordResetText(toName, resetUrl, workspaceName)
        send(to, toName, subject, html, text, tenant)
    }

    // -------------------------------------------------------------------------
    // Core send logic
    // -------------------------------------------------------------------------

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
    // Email templates — plain HTML, workspace name only
    // -------------------------------------------------------------------------

    private fun buildVerificationHtml(
        name: String,
        url: String,
        workspace: String,
    ) = """
        <!DOCTYPE html>
        <html lang="en">
        <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="margin:0;padding:0;font-family:sans-serif;background:#f4f4f5;color:#18181b;">
          <table width="100%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
            <tr><td align="center">
              <table width="480" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:8px;padding:40px;border:1px solid #e4e4e7;">
                <tr><td>
                  <p style="font-size:13px;color:#71717a;margin:0 0 8px 0;text-transform:uppercase;letter-spacing:.05em;">$workspace</p>
                  <h1 style="font-size:22px;margin:0 0 16px 0;color:#09090b;">Verify your email address</h1>
                  <p style="font-size:15px;line-height:1.6;color:#3f3f46;margin:0 0 24px 0;">
                    Hi ${htmlEscape(name)},<br><br>
                    Click the button below to verify your email address. This link expires in 24 hours.
                  </p>
                  <a href="$url" style="display:inline-block;padding:12px 24px;background:#18181b;color:#ffffff;border-radius:6px;text-decoration:none;font-size:14px;font-weight:600;">
                    Verify email address
                  </a>
                  <p style="font-size:12px;color:#71717a;margin:24px 0 0 0;line-height:1.5;">
                    If you did not create an account, you can safely ignore this email.<br>
                    If the button doesn't work, copy this link:<br>
                    <a href="$url" style="color:#71717a;word-break:break-all;">$url</a>
                  </p>
                </td></tr>
              </table>
            </td></tr>
          </table>
        </body>
        </html>
        """.trimIndent()

    private fun buildVerificationText(
        name: String,
        url: String,
        workspace: String,
    ) = """
        $workspace — Verify your email address

        Hi $name,

        Click the link below to verify your email address. This link expires in 24 hours.

        $url

        If you did not create an account, you can safely ignore this email.
        """.trimIndent()

    private fun buildPasswordResetHtml(
        name: String,
        url: String,
        workspace: String,
    ) = """
        <!DOCTYPE html>
        <html lang="en">
        <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="margin:0;padding:0;font-family:sans-serif;background:#f4f4f5;color:#18181b;">
          <table width="100%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
            <tr><td align="center">
              <table width="480" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:8px;padding:40px;border:1px solid #e4e4e7;">
                <tr><td>
                  <p style="font-size:13px;color:#71717a;margin:0 0 8px 0;text-transform:uppercase;letter-spacing:.05em;">$workspace</p>
                  <h1 style="font-size:22px;margin:0 0 16px 0;color:#09090b;">Reset your password</h1>
                  <p style="font-size:15px;line-height:1.6;color:#3f3f46;margin:0 0 24px 0;">
                    Hi ${htmlEscape(name)},<br><br>
                    We received a request to reset your password. Click the button below to choose a new one.
                    This link expires in 1 hour.
                  </p>
                  <a href="$url" style="display:inline-block;padding:12px 24px;background:#18181b;color:#ffffff;border-radius:6px;text-decoration:none;font-size:14px;font-weight:600;">
                    Reset password
                  </a>
                  <p style="font-size:12px;color:#71717a;margin:24px 0 0 0;line-height:1.5;">
                    If you did not request a password reset, you can safely ignore this email.<br>
                    If the button doesn't work, copy this link:<br>
                    <a href="$url" style="color:#71717a;word-break:break-all;">$url</a>
                  </p>
                </td></tr>
              </table>
            </td></tr>
          </table>
        </body>
        </html>
        """.trimIndent()

    private fun buildPasswordResetText(
        name: String,
        url: String,
        workspace: String,
    ) = """
        $workspace — Reset your password

        Hi $name,

        We received a request to reset your password. Click the link below to choose a new one.
        This link expires in 1 hour.

        $url

        If you did not request a password reset, you can safely ignore this email.
        """.trimIndent()

    private fun htmlEscape(s: String) =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
