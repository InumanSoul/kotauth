package com.kauth.adapter.web

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.TranslationPort

data class ViewContext(
    val theme: TenantTheme,
    val workspaceName: String,
    val locale: String,
    val translator: TranslationPort,
) {
    fun t(
        key: String,
        vararg args: Any?,
    ): String = translator.t(key, locale, *args)

    companion object {
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
