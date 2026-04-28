package com.kauth.adapter.web.auth

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.TranslationPort
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for `ApplicationCall.resolveLocale` — the per-request locale picker
 * used to build a `ViewContext`.
 */
class LocaleResolutionTest {
    private val translationWithEnEs =
        object : TranslationPort {
            override val availableLocales = setOf("en", "es")

            override fun t(
                key: String,
                locale: String,
                vararg args: Any?,
            ): String = key
        }

    private val englishOnlyTranslation =
        object : TranslationPort {
            override val availableLocales = setOf("en")

            override fun t(
                key: String,
                locale: String,
                vararg args: Any?,
            ): String = key
        }

    private fun tenant(defaultLocale: String? = null): Tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT.copy(defaultLocale = defaultLocale),
        )

    private fun runResolution(
        translation: TranslationPort,
        tenantArg: Tenant?,
        acceptLanguage: String?,
    ): String =
        runCaught {
            var result = ""
            testApplication {
                application {
                    routing {
                        get("/probe") {
                            result = call.resolveLocale(tenantArg, translation)
                            call.respondText(result)
                        }
                    }
                }
                client.get("/probe") {
                    if (acceptLanguage != null) header("Accept-Language", acceptLanguage)
                }
            }
            result
        }

    private fun <T> runCaught(block: () -> T): T = block()

    // =========================================================================
    // Accept-Language wins when available
    // =========================================================================

    @Test
    fun `Accept-Language es is honored when available`() {
        assertEquals("es", runResolution(translationWithEnEs, tenant(), "es"))
    }

    @Test
    fun `Accept-Language es-MX falls back to primary tag es when es is loaded`() {
        assertEquals("es", runResolution(translationWithEnEs, tenant(), "es-MX,es;q=0.9"))
    }

    @Test
    fun `first matching tag in Accept-Language wins`() {
        // de is not available; es is — picker walks the list in order.
        assertEquals("es", runResolution(translationWithEnEs, tenant(), "de,es;q=0.8,en;q=0.5"))
    }

    @Test
    fun `Accept-Language with no available tag falls through to tenant default`() {
        // Header asks fr-only → not available → tenant default es applies
        assertEquals(
            "es",
            runResolution(translationWithEnEs, tenant(defaultLocale = "es"), "fr"),
        )
    }

    @Test
    fun `Accept-Language with no available tag and no tenant default returns English`() {
        assertEquals("en", runResolution(translationWithEnEs, tenant(), "fr,de"))
    }

    // =========================================================================
    // Tenant default
    // =========================================================================

    @Test
    fun `tenant default es is honored when no Accept-Language header`() {
        assertEquals("es", runResolution(translationWithEnEs, tenant(defaultLocale = "es"), null))
    }

    @Test
    fun `tenant default es is normalized when stored uppercase`() {
        // Defensive: a stale uppercase value still matches "es" in available set
        assertEquals("es", runResolution(translationWithEnEs, tenant(defaultLocale = "ES"), null))
    }

    @Test
    fun `tenant default unloaded locale falls through to English`() {
        // Tenant has fr set but only en+es bundles loaded → English
        assertEquals("en", runResolution(translationWithEnEs, tenant(defaultLocale = "fr"), null))
    }

    // =========================================================================
    // English-only deployment
    // =========================================================================

    @Test
    fun `English-only deployment ignores Accept-Language and tenant defaults`() {
        // No bundles mounted → only "en" is available regardless of preference
        assertEquals(
            "en",
            runResolution(englishOnlyTranslation, tenant(defaultLocale = "es"), "es-MX,es;q=0.9"),
        )
    }

    // =========================================================================
    // Empty / null Accept-Language
    // =========================================================================

    @Test
    fun `null tenant and no header returns English`() {
        assertEquals("en", runResolution(translationWithEnEs, null, null))
    }

    @Test
    fun `empty Accept-Language header is treated as missing`() {
        assertEquals(
            "es",
            runResolution(translationWithEnEs, tenant(defaultLocale = "es"), ""),
        )
    }
}
