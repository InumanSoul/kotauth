package com.kauth.adapter.web.auth

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.TranslationPort
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

internal fun ApplicationCall.resolveLocale(
    tenant: Tenant?,
    translation: TranslationPort,
): String {
    val available = translation.availableLocales

    tenant?.theme?.defaultLocale?.lowercase()?.let { preferred ->
        if (preferred in available) return preferred
    }

    request.header("Accept-Language")?.let { header ->
        for (tag in parseAcceptLanguage(header)) {
            if (tag in available) return tag
            val primary = tag.substringBefore("-")
            if (primary in available) return primary
        }
    }

    return "en"
}

private fun parseAcceptLanguage(header: String): List<String> =
    header
        .split(",")
        .map { entry -> entry.substringBefore(";").trim().lowercase() }
        .filter { it.isNotBlank() }
