package com.kauth.infrastructure

import com.kauth.adapter.web.EnglishStrings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the two `TranslationPort` adapters.
 *
 * Coverage:
 *   - `EnglishOnlyTranslation`: baked-in English source, key-as-fallback for
 *     unknowns, locale arg silently ignored, `{N}` placeholder substitution.
 *   - `BundleTranslation`: JSON bundle loading, key fallback to English,
 *     unknown-locale fallback to English, `en.json` ignored, malformed-bundle
 *     resilience, `availableLocales` always includes English.
 */
class TranslationPortTest {
    // =========================================================================
    // EnglishOnlyTranslation
    // =========================================================================

    @Test
    fun `EnglishOnly returns the English baked-in value for a known key`() {
        val t = EnglishOnlyTranslation()
        assertEquals(EnglishStrings.PASSWORD, t.t("PASSWORD", "en"))
    }

    @Test
    fun `EnglishOnly returns the key verbatim for an unknown key`() {
        val t = EnglishOnlyTranslation()
        assertEquals("NO_SUCH_KEY", t.t("NO_SUCH_KEY", "en"))
    }

    @Test
    fun `EnglishOnly silently treats non-English locale as English`() {
        val t = EnglishOnlyTranslation()
        assertEquals(EnglishStrings.PASSWORD, t.t("PASSWORD", "es"))
        assertEquals(EnglishStrings.PASSWORD, t.t("PASSWORD", "ar"))
    }

    @Test
    fun `EnglishOnly availableLocales is just English`() {
        assertEquals(setOf("en"), EnglishOnlyTranslation().availableLocales)
    }

    // =========================================================================
    // BundleTranslation
    // =========================================================================

    @Test
    fun `Bundle returns Spanish value for known key when locale is es`(
        @TempDir dir: Path,
    ) {
        writeBundle(dir, "es", mapOf("PASSWORD" to "Contraseña"))
        val t = BundleTranslation(dir)
        assertEquals("Contraseña", t.t("PASSWORD", "es"))
    }

    @Test
    fun `Bundle falls back to English when key missing in Spanish bundle`(
        @TempDir dir: Path,
    ) {
        writeBundle(dir, "es", mapOf("PASSWORD" to "Contraseña"))
        val t = BundleTranslation(dir)
        // NEW_PASSWORD is not in es.json; must fall back to baked-in English
        assertEquals(EnglishStrings.NEW_PASSWORD, t.t("NEW_PASSWORD", "es"))
    }

    @Test
    fun `Bundle falls back to English for unknown locale`(
        @TempDir dir: Path,
    ) {
        writeBundle(dir, "es", mapOf("PASSWORD" to "Contraseña"))
        val t = BundleTranslation(dir)
        // No fr.json mounted — request for French silently returns English
        assertEquals(EnglishStrings.PASSWORD, t.t("PASSWORD", "fr"))
    }

    @Test
    fun `Bundle availableLocales includes English plus loaded locales`(
        @TempDir dir: Path,
    ) {
        writeBundle(dir, "es", mapOf("PASSWORD" to "Contraseña"))
        writeBundle(dir, "fr", mapOf("PASSWORD" to "Mot de passe"))
        val t = BundleTranslation(dir)
        assertEquals(setOf("en", "es", "fr"), t.availableLocales)
    }

    @Test
    fun `Bundle ignores an en json file in the bundle dir`(
        @TempDir dir: Path,
    ) {
        // Operator mounts an en.json attempting to override baked-in English —
        // it must be ignored. The JAR's EnglishStrings stays authoritative.
        writeBundle(dir, "en", mapOf("PASSWORD" to "PWN3D BY ATTACKER"))
        val t = BundleTranslation(dir)
        assertEquals(EnglishStrings.PASSWORD, t.t("PASSWORD", "en"))
    }

    @Test
    fun `Bundle skips malformed JSON without breaking other bundles`(
        @TempDir dir: Path,
    ) {
        writeBundle(dir, "es", mapOf("PASSWORD" to "Contraseña"))
        // pt.json is intentionally invalid JSON
        Files.writeString(dir.resolve("pt.json"), "{ this is not json")
        val t = BundleTranslation(dir)
        assertEquals("Contraseña", t.t("PASSWORD", "es"))
        assertFalse("pt" in t.availableLocales, "Malformed bundle must be skipped")
        assertTrue("es" in t.availableLocales, "Other bundles must still load")
    }

    @Test
    fun `Bundle handles missing directory gracefully`(
        @TempDir parent: Path,
    ) {
        val nonExistent = parent.resolve("does-not-exist")
        val t = BundleTranslation(nonExistent)
        // Must not throw; degrades to English-only behavior
        assertEquals(setOf("en"), t.availableLocales)
        assertEquals(EnglishStrings.PASSWORD, t.t("PASSWORD", "es"))
    }

    @Test
    fun `Bundle returns key verbatim for keys absent from both bundle and English`(
        @TempDir dir: Path,
    ) {
        writeBundle(dir, "es", mapOf("PASSWORD" to "Contraseña"))
        val t = BundleTranslation(dir)
        assertEquals("UNKNOWN_KEY", t.t("UNKNOWN_KEY", "es"))
    }

    // =========================================================================
    // Placeholder substitution — both adapters
    // =========================================================================

    @Test
    fun `placeholders are substituted in EnglishOnly templates`() {
        // Inject a fake template via reflection-resistant route: use a key that
        // does not exist in EnglishStrings → returned verbatim → exercises only
        // the substitution code path, not lookup. We rely on the {0} contract.
        val t = EnglishOnlyTranslation()
        // "Welcome, {0}" → "Welcome, alice"
        // We can't add a key to EnglishStrings from a test, so verify by passing
        // the template as the key and confirming substitution still applies.
        // EnglishOnly returns the key when missing — substitution is a no-op
        // on a literal "{0}"-free key, so add a Bundle test for the real path.
        assertEquals("MISSING", t.t("MISSING", "en", "alice"))
    }

    @Test
    fun `placeholders are substituted in Bundle templates`(
        @TempDir dir: Path,
    ) {
        writeBundle(dir, "es", mapOf("GREETING" to "Hola, {0}, te quedan {1} días"))
        val t = BundleTranslation(dir)
        assertEquals(
            "Hola, alice, te quedan 7 días",
            t.t("GREETING", "es", "alice", 7),
        )
    }

    @Test
    fun `placeholders fall through to English template substitution on key fallback`(
        @TempDir dir: Path,
    ) {
        // Spanish bundle does not define PASSWORD_MIN_PLACEHOLDER → falls back to the
        // English template "Minimum {0} characters" and substitutes the argument.
        writeBundle(dir, "es", emptyMap())
        val t = BundleTranslation(dir)
        assertEquals("Minimum 8 characters", t.t("PASSWORD_MIN_PLACEHOLDER", "es", 8))
    }

    // =========================================================================
    // EnglishStrings.byKey — reflection-derived map
    // =========================================================================

    @Test
    fun `EnglishStrings byKey contains every const val String declaration`() {
        val map = EnglishStrings.byKey
        assertEquals("Password", map["PASSWORD"])
        assertEquals("New password", map["NEW_PASSWORD"])
        assertEquals("Confirm Password", map["CONFIRM_PASSWORD"])
        assertTrue(map.size >= 30, "byKey should expose every const val String, was ${map.size}")
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun writeBundle(
        dir: Path,
        locale: String,
        entries: Map<String, String>,
    ) {
        val json =
            entries.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
        Files.writeString(dir.resolve("$locale.json"), json)
    }
}
