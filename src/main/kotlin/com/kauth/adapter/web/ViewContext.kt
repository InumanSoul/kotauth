package com.kauth.adapter.web

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.TranslationPort

/**
 * Per-request rendering context for `kotlinx.html` views.
 *
 * Replaces the historic `theme + workspaceName` parameter pair with a single
 * cohesive bundle that also carries a translator pre-bound to the resolved
 * locale. Views call `ctx.t("KEY", arg)` rather than threading a locale or
 * `TranslationPort` reference through every signature.
 *
 * Constructed once per request by route handlers — see `resolveLocale` and
 * the auth route intercept that resolves tenant context.
 */
data class ViewContext(
    val theme: TenantTheme,
    val workspaceName: String,
    val locale: String,
    val translator: TranslationPort,
) {
    /**
     * Translates [key] under the request's resolved locale, substituting
     * `{0}`, `{1}`, … placeholders from [args]. See
     * [TranslationPort.t] for fallback semantics.
     */
    fun t(
        key: String,
        vararg args: Any?,
    ): String = translator.t(key, locale, *args)

    companion object {
        /**
         * Convenience factory matching the historic `theme + workspaceName`
         * call sites. The default English locale + `EnglishOnlyTranslation`
         * preserves byte-identical view output for callers that have not
         * yet migrated to locale-aware rendering.
         */
        fun englishOnly(
            theme: TenantTheme,
            workspaceName: String,
            translator: TranslationPort,
        ): ViewContext =
            ViewContext(
                theme = theme,
                workspaceName = workspaceName,
                locale = "en",
                translator = translator,
            )
    }
}
