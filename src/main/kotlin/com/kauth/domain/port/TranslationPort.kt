package com.kauth.domain.port

interface TranslationPort {
    val availableLocales: Set<String>

    fun t(
        key: String,
        locale: String = "en",
        vararg args: Any?,
    ): String
}
