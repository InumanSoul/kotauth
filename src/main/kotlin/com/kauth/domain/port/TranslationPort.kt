package com.kauth.domain.port

/**
 * Output port — translates a UI string key to a locale-specific value.
 *
 * The default implementation is `EnglishOnlyTranslation` (English baked into
 * the JAR). Operators opt into additional locales by mounting JSON bundles
 * via `KAUTH_I18N_BUNDLE_DIR`, which switches the wired adapter to
 * `BundleTranslation`.
 *
 * Contract:
 *   - English is always available — `t(key, "en")` MUST succeed for any key
 *     present in `EnglishStrings`. Implementations fall back to English when
 *     a non-English bundle is missing the requested key.
 *   - Unknown keys return the key itself as a debug-friendly placeholder
 *     rather than throwing or returning an empty string.
 *   - Implementations MUST be thread-safe — bundles are loaded once at
 *     startup and shared across all request handlers.
 */
interface TranslationPort {
    /** Locales this implementation has loaded — always includes `"en"`. */
    val availableLocales: Set<String>

    /**
     * Looks up [key] in the [locale]'s bundle. Falls back to English on
     * missing keys. Returns [key] verbatim if neither locale nor English
     * defines it (debug-friendly: missing translations are visually obvious).
     *
     * [args], if provided, replace `{0}`, `{1}`, … placeholders in the
     * resolved template using `String.toString()`.
     */
    fun t(
        key: String,
        locale: String = "en",
        vararg args: Any?,
    ): String
}
