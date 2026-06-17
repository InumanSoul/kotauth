package com.kauth.adapter.email

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.EmailPort
import com.kauth.domain.port.TranslationPort
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

internal data class RenderedEmail(
    val subject: String,
    val html: String,
    val text: String,
)

class SmtpEmailAdapter(
    private val translationPort: TranslationPort,
) : EmailPort {
    private val log = LoggerFactory.getLogger(SmtpEmailAdapter::class.java)

    override fun sendVerificationEmail(
        to: String,
        toName: String,
        verifyUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val r = renderVerification(toName, verifyUrl, workspaceName, tenant)
        send(to, toName, r.subject, r.html, r.text, tenant)
    }

    override fun sendPasswordResetEmail(
        to: String,
        toName: String,
        resetUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val r = renderPasswordReset(toName, resetUrl, workspaceName, tenant)
        send(to, toName, r.subject, r.html, r.text, tenant)
    }

    override fun sendAccountLockedEmail(
        to: String,
        toName: String,
        resetUrl: String,
        workspaceName: String,
        lockoutDuration: String,
        tenant: Tenant,
    ) {
        val r = renderAccountLocked(toName, resetUrl, workspaceName, lockoutDuration, tenant)
        send(to, toName, r.subject, r.html, r.text, tenant)
    }

    override fun sendPasswordChangedEmail(
        to: String,
        toName: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val r = renderPasswordChanged(toName, workspaceName, tenant)
        send(to, toName, r.subject, r.html, r.text, tenant)
    }

    override fun sendTestEmail(
        to: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val r = renderTest(workspaceName, tenant)
        send(to, to, r.subject, r.html, r.text, tenant)
    }

    override fun sendInviteEmail(
        to: String,
        toName: String,
        inviteUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val r = renderInvite(toName, inviteUrl, workspaceName, tenant)
        send(to, toName, r.subject, r.html, r.text, tenant)
    }

    override fun sendMagicLinkEmail(
        to: String,
        toName: String,
        magicLinkUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val r = renderMagicLink(toName, magicLinkUrl, workspaceName, tenant)
        send(to, toName, r.subject, r.html, r.text, tenant)
    }

    override fun sendEmailOtpEmail(
        to: String,
        toName: String,
        code: String,
        expiresInMinutes: Long,
        workspaceName: String,
        tenant: Tenant,
    ) {
        val r = renderEmailOtp(toName, code, expiresInMinutes, workspaceName, tenant)
        send(to, toName, r.subject, r.html, r.text, tenant)
    }

    // -------------------------------------------------------------------------
    // Render functions — pure (no I/O) so they're directly testable.
    // -------------------------------------------------------------------------

    internal fun renderVerification(
        toName: String,
        verifyUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ): RenderedEmail {
        val locale = emailLocale(tenant)
        return RenderedEmail(
            subject = t("EMAIL_SUBJECT_VERIFY", locale, workspaceName),
            html =
                buildEmailHtml(
                    tenant = tenant,
                    locale = locale,
                    heading = t("EMAIL_HEADING_VERIFY", locale),
                    bodyHtml = greetingHtml(toName, locale) + t("EMAIL_BODY_VERIFY", locale),
                    ctaLabel = t("EMAIL_CTA_VERIFY", locale),
                    ctaUrl = verifyUrl,
                    footerHtml = t("EMAIL_FOOTER_VERIFY", locale),
                ),
            text =
                buildEmailText(
                    workspace = workspaceName,
                    heading = t("EMAIL_HEADING_VERIFY", locale),
                    body = greetingText(toName, locale) + t("EMAIL_BODY_VERIFY", locale),
                    url = verifyUrl,
                    footer = t("EMAIL_FOOTER_VERIFY", locale),
                ),
        )
    }

    internal fun renderPasswordReset(
        toName: String,
        resetUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ): RenderedEmail {
        val locale = emailLocale(tenant)
        return RenderedEmail(
            subject = t("EMAIL_SUBJECT_PASSWORD_RESET", locale, workspaceName),
            html =
                buildEmailHtml(
                    tenant = tenant,
                    locale = locale,
                    heading = t("EMAIL_HEADING_PASSWORD_RESET", locale),
                    bodyHtml = greetingHtml(toName, locale) + t("EMAIL_BODY_PASSWORD_RESET", locale),
                    ctaLabel = t("EMAIL_CTA_PASSWORD_RESET", locale),
                    ctaUrl = resetUrl,
                    footerHtml = t("EMAIL_FOOTER_PASSWORD_RESET", locale),
                ),
            text =
                buildEmailText(
                    workspace = workspaceName,
                    heading = t("EMAIL_HEADING_PASSWORD_RESET", locale),
                    body = greetingText(toName, locale) + t("EMAIL_BODY_PASSWORD_RESET", locale),
                    url = resetUrl,
                    footer = t("EMAIL_FOOTER_PASSWORD_RESET", locale),
                ),
        )
    }

    internal fun renderAccountLocked(
        toName: String,
        resetUrl: String,
        workspaceName: String,
        lockoutDuration: String,
        tenant: Tenant,
    ): RenderedEmail {
        val locale = emailLocale(tenant)
        return RenderedEmail(
            subject = t("EMAIL_SUBJECT_ACCOUNT_LOCKED", locale, workspaceName),
            html =
                buildEmailHtml(
                    tenant = tenant,
                    locale = locale,
                    heading = t("EMAIL_HEADING_ACCOUNT_LOCKED", locale),
                    bodyHtml =
                        greetingHtml(toName, locale) +
                            t(
                                "EMAIL_BODY_ACCOUNT_LOCKED",
                                locale,
                                htmlEscape(workspaceName),
                                htmlEscape(lockoutDuration),
                            ),
                    ctaLabel = t("EMAIL_CTA_PASSWORD_RESET", locale),
                    ctaUrl = resetUrl,
                    footerHtml = t("EMAIL_FOOTER_ACCOUNT_LOCKED", locale),
                ),
            text =
                buildEmailText(
                    workspace = workspaceName,
                    heading = t("EMAIL_HEADING_ACCOUNT_LOCKED", locale),
                    body =
                        greetingText(toName, locale) +
                            t("EMAIL_BODY_ACCOUNT_LOCKED", locale, workspaceName, lockoutDuration),
                    url = resetUrl,
                    footer = t("EMAIL_FOOTER_ACCOUNT_LOCKED", locale),
                ),
        )
    }

    internal fun renderPasswordChanged(
        toName: String,
        workspaceName: String,
        tenant: Tenant,
    ): RenderedEmail {
        val locale = emailLocale(tenant)
        val loginUrl = tenant.issuerUrl?.replace("/t/${tenant.slug}", "/t/${tenant.slug}/account/login") ?: ""

        val baseBodyHtml =
            greetingHtml(toName, locale) +
                t("EMAIL_BODY_PASSWORD_CHANGED", locale, htmlEscape(workspaceName))
        val loginHintHtml =
            if (loginUrl.isNotBlank()) {
                val anchor =
                    """ <a href="${htmlEscape(loginUrl)}" style="color:#71717a;">${htmlEscape(loginUrl)}</a>"""
                " " + t("EMAIL_BODY_PASSWORD_CHANGED_LOGIN_HINT", locale, anchor)
            } else {
                ""
            }
        val baseBodyText =
            greetingText(toName, locale) + t("EMAIL_BODY_PASSWORD_CHANGED", locale, workspaceName)
        val loginHintText =
            if (loginUrl.isNotBlank()) "\n" + t("EMAIL_BODY_PASSWORD_CHANGED_LOGIN_HINT", locale, loginUrl) else ""

        return RenderedEmail(
            subject = t("EMAIL_SUBJECT_PASSWORD_CHANGED", locale, workspaceName),
            html =
                buildEmailHtml(
                    tenant = tenant,
                    locale = locale,
                    heading = t("EMAIL_HEADING_PASSWORD_CHANGED", locale),
                    bodyHtml = baseBodyHtml + loginHintHtml,
                    footerHtml = t("EMAIL_FOOTER_PASSWORD_CHANGED", locale),
                ),
            text =
                buildEmailText(
                    workspace = workspaceName,
                    heading = t("EMAIL_HEADING_PASSWORD_CHANGED", locale),
                    body = baseBodyText + loginHintText,
                    url = null,
                    footer = t("EMAIL_FOOTER_PASSWORD_CHANGED", locale),
                ),
        )
    }

    internal fun renderTest(
        workspaceName: String,
        tenant: Tenant,
    ): RenderedEmail {
        val locale = emailLocale(tenant)
        return RenderedEmail(
            subject = t("EMAIL_SUBJECT_TEST", locale, workspaceName),
            html =
                buildEmailHtml(
                    tenant = tenant,
                    locale = locale,
                    heading = t("EMAIL_HEADING_TEST", locale),
                    bodyHtml = t("EMAIL_BODY_TEST", locale, "<strong>${htmlEscape(workspaceName)}</strong>"),
                    footerHtml = t("EMAIL_FOOTER_TEST", locale),
                ),
            text =
                buildEmailText(
                    workspace = workspaceName,
                    heading = t("EMAIL_HEADING_TEST", locale),
                    body = t("EMAIL_BODY_TEST", locale, workspaceName),
                    url = null,
                    footer = t("EMAIL_FOOTER_TEST", locale),
                ),
        )
    }

    internal fun renderInvite(
        toName: String,
        inviteUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ): RenderedEmail {
        val locale = emailLocale(tenant)
        return RenderedEmail(
            subject = t("EMAIL_SUBJECT_INVITE", locale, workspaceName),
            html =
                buildEmailHtml(
                    tenant = tenant,
                    locale = locale,
                    heading = t("EMAIL_HEADING_INVITE", locale),
                    bodyHtml =
                        greetingHtml(toName, locale) +
                            t("EMAIL_BODY_INVITE", locale, "<strong>${htmlEscape(workspaceName)}</strong>"),
                    ctaLabel = t("EMAIL_CTA_INVITE", locale),
                    ctaUrl = inviteUrl,
                    footerHtml = t("EMAIL_FOOTER_INVITE", locale),
                ),
            text =
                buildEmailText(
                    workspace = workspaceName,
                    heading = t("EMAIL_HEADING_INVITE", locale),
                    body = greetingText(toName, locale) + t("EMAIL_BODY_INVITE", locale, workspaceName),
                    url = inviteUrl,
                    footer = t("EMAIL_FOOTER_INVITE", locale),
                ),
        )
    }

    internal fun renderMagicLink(
        toName: String,
        magicLinkUrl: String,
        workspaceName: String,
        tenant: Tenant,
    ): RenderedEmail {
        val locale = emailLocale(tenant)
        return RenderedEmail(
            subject = t("EMAIL_SUBJECT_MAGIC_LINK", locale, workspaceName),
            html =
                buildEmailHtml(
                    tenant = tenant,
                    locale = locale,
                    heading = t("EMAIL_HEADING_MAGIC_LINK", locale, htmlEscape(workspaceName)),
                    bodyHtml = greetingHtml(toName, locale) + t("EMAIL_BODY_MAGIC_LINK", locale),
                    ctaLabel = t("EMAIL_CTA_MAGIC_LINK", locale),
                    ctaUrl = magicLinkUrl,
                    footerHtml = t("EMAIL_FOOTER_MAGIC_LINK", locale),
                ),
            text =
                buildEmailText(
                    workspace = workspaceName,
                    heading = t("EMAIL_HEADING_MAGIC_LINK", locale, workspaceName),
                    body = greetingText(toName, locale) + t("EMAIL_BODY_MAGIC_LINK", locale),
                    url = magicLinkUrl,
                    footer = t("EMAIL_FOOTER_MAGIC_LINK", locale),
                ),
        )
    }

    internal fun renderEmailOtp(
        toName: String,
        code: String,
        expiresInMinutes: Long,
        workspaceName: String,
        tenant: Tenant,
    ): RenderedEmail {
        val locale = emailLocale(tenant)
        val codeBlock =
            """
            <div style="font-family:monospace;font-size:32px;letter-spacing:6px;font-weight:600;padding:20px;border-radius:${htmlEscape(
                tenant.theme.borderRadius,
            )};background:#f4f4f5;color:#09090b;text-align:center;margin:0 0 24px 0;">
              ${htmlEscape(code)}
            </div>
            """.trimIndent()
        return RenderedEmail(
            subject = t("EMAIL_SUBJECT_OTP", locale, workspaceName),
            html =
                buildEmailHtml(
                    tenant = tenant,
                    locale = locale,
                    heading = t("EMAIL_HEADING_OTP", locale),
                    bodyHtml = greetingHtml(toName, locale) + t("EMAIL_BODY_OTP", locale, expiresInMinutes),
                    footerHtml = t("EMAIL_FOOTER_OTP", locale),
                    embeddedHtml = codeBlock,
                ),
            text =
                buildEmailText(
                    workspace = workspaceName,
                    heading = t("EMAIL_HEADING_OTP", locale),
                    body =
                        greetingText(toName, locale) +
                            t("EMAIL_BODY_OTP", locale, expiresInMinutes) +
                            "\n\n    $code",
                    url = null,
                    footer = t("EMAIL_FOOTER_OTP", locale),
                ),
        )
    }

    // -------------------------------------------------------------------------
    // Locale + translation helpers
    // -------------------------------------------------------------------------

    private fun emailLocale(tenant: Tenant): String {
        val configured = tenant.theme.defaultLocale?.lowercase() ?: return "en"
        return if (configured in translationPort.availableLocales) configured else "en"
    }

    private fun t(
        key: String,
        locale: String,
        vararg args: Any?,
    ): String = translationPort.t(key, locale, *args)

    private fun greetingHtml(
        name: String,
        locale: String,
    ): String = t("EMAIL_GREETING", locale, htmlEscape(name)) + "<br><br>"

    private fun greetingText(
        name: String,
        locale: String,
    ): String = t("EMAIL_GREETING", locale, name) + "\n\n"

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
        val useSsl = (port == 465)

        val hasAuth = !tenant.smtpUsername.isNullOrBlank() && !tenant.smtpPassword.isNullOrBlank()

        val props =
            Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.auth", if (hasAuth) "true" else "false")

                when {
                    useSsl -> {
                        put("mail.smtp.ssl.enable", "true")
                        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                    }
                    tenant.smtpTlsEnabled -> {
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
                tenant.emailBranding?.fromDisplayName
                    ?: tenant.smtpFromName
                    ?: workspaceDisplayName(tenant),
            )

        val message =
            MimeMessage(session).apply {
                setFrom(fromAddress)
                setRecipient(Message.RecipientType.TO, InternetAddress(to, toName))
                setSubject(subject, "UTF-8")

                val htmlPart = MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") }
                val textPart = MimeBodyPart().apply { setContent(text, "text/plain; charset=UTF-8") }

                val multipart =
                    MimeMultipart("alternative").apply {
                        addBodyPart(textPart)
                        addBodyPart(htmlPart)
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

    private fun workspaceDisplayName(tenant: Tenant) = tenant.emailBranding?.brandName ?: tenant.displayName

    private fun buildEmailHtml(
        tenant: Tenant,
        locale: String,
        heading: String,
        bodyHtml: String,
        ctaLabel: String? = null,
        ctaUrl: String? = null,
        footerHtml: String,
        embeddedHtml: String? = null,
    ): String {
        val theme = tenant.theme
        val branding = tenant.emailBranding
        val workspace = htmlEscape(branding?.brandName ?: tenant.displayName)
        val font = "${htmlEscape(theme.fontFamily)}, sans-serif"
        val accent = branding?.brandColorHex ?: theme.accentColor
        val logoUrl = branding?.brandLogoUrl ?: theme.logoUrl

        val logoSection =
            if (logoUrl != null) {
                """<img src="${htmlEscape(logoUrl)}" alt="$workspace" border="0" """ +
                    """style="max-height:40px;max-width:200px;margin:0 0 16px 0;display:block;">"""
            } else {
                ""
            }

        val safeCtaUrl = ctaUrl?.let { htmlEscape(it) }
        val safeCtaLabel = ctaLabel?.let { htmlEscape(it) }
        val radius = htmlEscape(theme.borderRadius)
        val supportLine =
            branding?.supportEmail?.let {
                """<br>Need help? Contact <a href="mailto:${htmlEscape(it)}" """ +
                    """style="color:#71717a;">${htmlEscape(it)}</a>."""
            } ?: ""

        val ctaSection =
            if (safeCtaLabel != null && safeCtaUrl != null) {
                """
                <a href="$safeCtaUrl" style="display:inline-block;padding:12px 24px;background:$accent;color:${theme.accentForeground};border-radius:$radius;text-decoration:none;font-size:14px;font-weight:600;">
                  $safeCtaLabel
                </a>
                <p style="font-size:12px;color:#71717a;margin:24px 0 0 0;line-height:1.5;">
                  $footerHtml$supportLine<br>
                  If the button doesn't work, copy this link:<br>
                  <a href="$safeCtaUrl" style="color:#71717a;word-break:break-all;">$safeCtaUrl</a>
                </p>
                """.trimIndent()
            } else {
                """
                ${embeddedHtml ?: ""}
                <p style="font-size:12px;color:#71717a;margin:24px 0 0 0;line-height:1.5;">
                  $footerHtml$supportLine
                </p>
                """.trimIndent()
            }

        return """
            <!DOCTYPE html>
            <html lang="$locale">
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

    private fun htmlEscape(s: String) =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
