package com.kauth.adapter.web

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.TranslationPort

/**
 * When the current portal page is being viewed under an admin impersonation,
 * this carries the admin's display name so the banner can render
 * "Signed in as {admin}" without re-querying. Null on normal portal sessions.
 */
data class ImpersonationContext(
    val adminUsername: String,
    val targetUsername: String,
)

data class ViewContext(
    val theme: TenantTheme,
    val workspaceName: String,
    val locale: String,
    val translator: TranslationPort,
    val impersonation: ImpersonationContext? = null,
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
