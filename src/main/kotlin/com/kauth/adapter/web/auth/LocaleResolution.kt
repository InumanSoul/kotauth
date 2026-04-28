package com.kauth.adapter.web.auth

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.TranslationPort
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Resolves the locale code to use for a request.
 *
 * Resolution priority:
 *   1. The first `Accept-Language` tag whose primary language matches an
 *      `availableLocales` entry. Region subtags (`es-MX`) match the bare
 *      language (`es`) when the region itself isn't loaded.
 *   2. The tenant's `defaultLocale` from `TenantTheme`, if set and loaded.
 *   3. `"en"` — always available.
 *
 * Locale codes are compared lower-case throughout. Operators are responsible
 * for naming bundle files in lowercase (`es.json`, not `ES.json`).
 */
internal fun ApplicationCall.resolveLocale(
    tenant: Tenant?,
    translation: TranslationPort,
): String {
    val available = translation.availableLocales

    request.header("Accept-Language")?.let { header ->
        for (tag in parseAcceptLanguage(header)) {
            if (tag in available) return tag
            val primary = tag.substringBefore("-")
            if (primary in available) return primary
        }
    }

    tenant?.theme?.defaultLocale?.lowercase()?.let { preferred ->
        if (preferred in available) return preferred
    }

    return "en"
}

/**
 * Parses an `Accept-Language` header into an ordered list of language tags
 * (most-preferred first). Quality factors are honored in their textual order;
 * we deliberately do not sort by `q=` value because the `available` filter
 * downstream eliminates non-matching candidates anyway and re-ranking is rarely
 * what the user wants when they typed a region first (`es-MX,es;q=0.9`).
 */
private fun parseAcceptLanguage(header: String): List<String> =
    header
        .split(",")
        .map { entry -> entry.substringBefore(";").trim().lowercase() }
        .filter { it.isNotBlank() }
