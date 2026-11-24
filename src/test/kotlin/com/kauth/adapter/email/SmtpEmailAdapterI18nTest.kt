package com.kauth.adapter.email

import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.TranslationPort
import com.kauth.infrastructure.EnglishOnlyTranslation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmtpEmailAdapterI18nTest {
    private val englishOnlyTenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = "https://acme.example.com",
        )

    private val spanishTenant =
        englishOnlyTenant.copy(
            theme = TenantTheme.DEFAULT.copy(defaultLocale = "es"),
        )

    @Test
    fun `tenant without defaultLocale renders the English copy`() {
        val adapter = SmtpEmailAdapter(EnglishOnlyTranslation())

        val rendered = adapter.renderVerification("Alice", "https://example.com/verify?t=x", "Acme", englishOnlyTenant)

        assertEquals("Verify your email address (Acme)", rendered.subject)
        assertTrue(rendered.html.contains("Verify your email address"))
        assertTrue(rendered.html.contains("Click the button below to verify"))
        assertTrue(rendered.text.contains("Verify your email address"))
    }

    @Test
    fun `every factor change renders its own copy with the time and no link`() {
        val adapter = SmtpEmailAdapter(EnglishOnlyTranslation())
        val at = java.time.Instant.parse("2026-11-23T22:58:13Z")

        com.kauth.domain.model.FactorChange.entries.forEach { change ->
            val rendered = adapter.renderFactorChanged("Alice", change, 3, at, "Acme <Corp>", englishOnlyTenant)
            listOf(rendered.subject, rendered.html, rendered.text).forEach { part ->
                assertFalse(part.contains("EMAIL_"), "$change has an untranslated key")
            }
            assertTrue(rendered.text.contains("2026-11-23 22:58 UTC"), "$change")
            assertTrue(rendered.html.contains("Acme &lt;Corp&gt;"), "$change escapes the workspace name")
            assertFalse(rendered.html.contains("Acme <Corp>"), "$change")
            assertFalse(rendered.html.contains("<a "), "$change carries no link")
        }
        val recovery =
            adapter.renderFactorChanged(
                "Alice",
                com.kauth.domain.model.FactorChange.RECOVERY_CODE_USED,
                3,
                at,
                "Acme",
                englishOnlyTenant,
            )
        assertTrue(recovery.text.contains("You have 3 recovery codes left."))
    }

    @Test
    fun `tenant with es defaultLocale picks up Spanish bundle for subject and body`() {
        val translator =
            inMemoryTranslator(
                "es" to
                    mapOf(
                        "EMAIL_SUBJECT_VERIFY" to "Verifica tu correo — {0}",
                        "EMAIL_HEADING_VERIFY" to "Verifica tu correo",
                        "EMAIL_GREETING" to "Hola {0},",
                        "EMAIL_BODY_VERIFY" to "Pulsa el botón para verificar.",
                        "EMAIL_CTA_VERIFY" to "Verificar",
                        "EMAIL_FOOTER_VERIFY" to "Si no creaste una cuenta, ignora este correo.",
                    ),
            )
        val adapter = SmtpEmailAdapter(translator)

        val rendered = adapter.renderVerification("Alice", "https://example.com/verify?t=x", "Acme", spanishTenant)

        assertEquals("Verifica tu correo — Acme", rendered.subject)
        assertTrue(rendered.html.contains("Hola Alice,"))
        assertTrue(rendered.html.contains("Pulsa el botón para verificar."))
        assertTrue(rendered.html.contains("Verificar"))
        assertFalse(rendered.html.contains("Click the button below"), "English copy must not bleed through")
    }

    @Test
    fun `untranslated keys fall back to English in the same render`() {
        val translator =
            inMemoryTranslator(
                "es" to
                    mapOf(
                        "EMAIL_SUBJECT_VERIFY" to "Verifica tu correo — {0}",
                    ),
            )
        val adapter = SmtpEmailAdapter(translator)

        val rendered = adapter.renderVerification("Alice", "https://example.com/verify?t=x", "Acme", spanishTenant)

        assertEquals("Verifica tu correo — Acme", rendered.subject)
        assertTrue(rendered.html.contains("Verify your email address"), "Untranslated heading falls back to English")
        assertTrue(rendered.html.contains("Click the button below to verify"), "Body too")
    }

    @Test
    fun `defaultLocale that is not loaded falls back to English entirely`() {
        val translator =
            inMemoryTranslator(
                "es" to mapOf("EMAIL_SUBJECT_VERIFY" to "Verifica tu correo — {0}"),
            )
        val adapter = SmtpEmailAdapter(translator)
        val frTenant = englishOnlyTenant.copy(theme = TenantTheme.DEFAULT.copy(defaultLocale = "fr"))

        val rendered = adapter.renderVerification("Alice", "https://example.com/verify?t=x", "Acme", frTenant)

        assertEquals("Verify your email address (Acme)", rendered.subject)
    }

    @Test
    fun `recipient name is HTML-escaped in the HTML body but not the text body`() {
        val adapter = SmtpEmailAdapter(EnglishOnlyTranslation())
        val rendered =
            adapter.renderVerification(
                toName = "<script>alert(1)</script>",
                verifyUrl = "https://example.com/verify?t=x",
                workspaceName = "Acme",
                tenant = englishOnlyTenant,
            )

        assertTrue(rendered.html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(rendered.html.contains("<script>alert(1)</script>"), "Raw name must never reach the HTML body")
        assertTrue(rendered.text.contains("<script>alert(1)</script>"), "Text body is plaintext — no escaping needed")
    }

    @Test
    fun `account-locked email substitutes workspace and lockout duration into translated body`() {
        val translator =
            inMemoryTranslator(
                "es" to
                    mapOf(
                        "EMAIL_SUBJECT_ACCOUNT_LOCKED" to "Cuenta bloqueada — {0}",
                        "EMAIL_HEADING_ACCOUNT_LOCKED" to "Cuenta bloqueada",
                        "EMAIL_GREETING" to "Hola {0},",
                        "EMAIL_BODY_ACCOUNT_LOCKED" to "Bloqueamos tu cuenta de {0}. Se desbloqueará en {1}.",
                        "EMAIL_CTA_PASSWORD_RESET" to "Restablecer contraseña",
                        "EMAIL_FOOTER_ACCOUNT_LOCKED" to "Ignora si fuiste tú.",
                    ),
            )
        val adapter = SmtpEmailAdapter(translator)

        val rendered =
            adapter.renderAccountLocked(
                toName = "Alice",
                resetUrl = "https://example.com/reset",
                workspaceName = "Acme",
                lockoutDuration = "15 minutos",
                tenant = spanishTenant,
            )

        assertEquals("Cuenta bloqueada — Acme", rendered.subject)
        assertTrue(rendered.html.contains("Bloqueamos tu cuenta de Acme. Se desbloqueará en 15 minutos."))
        assertTrue(rendered.text.contains("Bloqueamos tu cuenta de Acme. Se desbloqueará en 15 minutos."))
    }

    @Test
    fun `OTP email substitutes expiry minutes and embeds the code verbatim`() {
        val adapter = SmtpEmailAdapter(EnglishOnlyTranslation())

        val rendered =
            adapter.renderEmailOtp(
                toName = "Alice",
                code = "482190",
                expiresInMinutes = 10,
                workspaceName = "Acme",
                tenant = englishOnlyTenant,
            )

        assertTrue(rendered.html.contains("Use this 10-minute code"))
        assertTrue(rendered.html.contains("482190"))
        assertTrue(rendered.text.contains("482190"))
    }

    @Test
    fun `magic-link email quotes the workspace's link expiry`() {
        val adapter = SmtpEmailAdapter(EnglishOnlyTranslation())
        val tenant = englishOnlyTenant.copy(securityConfig = SecurityConfig(magicLinkTokenTtlMinutes = 45))

        val rendered = adapter.renderMagicLink("Alice", "https://acme.example.com/ml", "Acme", tenant)

        assertTrue(rendered.html.contains("This link expires in 45 minutes"))
        assertTrue(rendered.text.contains("This link expires in 45 minutes"))
    }

    private fun inMemoryTranslator(vararg bundles: Pair<String, Map<String, String>>): TranslationPort {
        val map = bundles.toMap()
        val english = EnglishOnlyTranslation()
        return object : TranslationPort {
            override val availableLocales: Set<String> = map.keys + "en"

            override fun t(
                key: String,
                locale: String,
                vararg args: Any?,
            ): String {
                val template = map[locale]?.get(key) ?: return english.t(key, "en", *args)
                return args.foldIndexed(template) { index, acc, value ->
                    acc.replace("{$index}", value?.toString() ?: "")
                }
            }
        }
    }
}
