package com.kauth.domain.util

import com.kauth.domain.model.TenantTheme

private val COLOR_PATTERN = Regex("^#[0-9a-fA-F]{3,8}$")
private val FONT_FAMILY_PATTERN = Regex("^[A-Za-z0-9 .,'_-]+$")
private val BORDER_RADIUS_PATTERN = Regex("^[0-9]+(\\.[0-9]+)?(px|rem|em|%)$")
private val LOCALE_PATTERN = Regex("^[a-z]{2}(-[a-zA-Z]{2})?$")

fun validateTenantTheme(theme: TenantTheme): String? {
    validateColor(theme.accentColor, "accentColor")?.let { return it }
    validateColor(theme.accentHoverColor, "accentHoverColor")?.let { return it }
    validateColor(theme.accentForeground, "accentForeground")?.let { return it }
    validateColor(theme.bgDeep, "bgDeep")?.let { return it }
    validateColor(theme.surface, "surface")?.let { return it }
    validateColor(theme.bgInput, "bgInput")?.let { return it }
    validateColor(theme.borderColor, "borderColor")?.let { return it }
    validateColor(theme.textPrimary, "textPrimary")?.let { return it }
    validateColor(theme.textMuted, "textMuted")?.let { return it }
    if (!FONT_FAMILY_PATTERN.matches(theme.fontFamily)) {
        return "fontFamily must contain only letters, digits, spaces, and . , ' _ -"
    }
    if (!BORDER_RADIUS_PATTERN.matches(theme.borderRadius)) {
        return "borderRadius must be a number followed by px, rem, em, or % (e.g. \"8px\")"
    }
    if (theme.logoUrl != null && !isHttpUrl(theme.logoUrl)) {
        return "logoUrl must be an http or https URL"
    }
    if (theme.faviconUrl != null && !isHttpUrl(theme.faviconUrl)) {
        return "faviconUrl must be an http or https URL"
    }
    if (theme.defaultLocale != null && !LOCALE_PATTERN.matches(theme.defaultLocale)) {
        return "defaultLocale must be a BCP-47 code like \"en\" or \"en-US\""
    }
    return null
}

private fun validateColor(
    value: String,
    field: String,
): String? = if (COLOR_PATTERN.matches(value)) null else "$field must be a hex color like \"#1FBCFF\""

private fun isHttpUrl(url: String): Boolean =
    try {
        val uri = java.net.URI(url)
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
