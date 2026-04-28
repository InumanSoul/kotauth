package com.kauth.infrastructure

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.port.TranslationPort

class EnglishOnlyTranslation : TranslationPort {
    override val availableLocales: Set<String> = setOf("en")

    override fun t(
        key: String,
        locale: String,
        vararg args: Any?,
    ): String {
        val template = EnglishStrings.byKey[key] ?: return key
        return substitute(template, args)
    }

    private fun substitute(
        template: String,
        args: Array<out Any?>,
    ): String {
        if (args.isEmpty()) return template
        return args.foldIndexed(template) { index, acc, value ->
            acc.replace("{$index}", value?.toString() ?: "")
        }
    }
}
