package com.kauth.domain.model

/**
 * Value object holding the visual identity of a tenant's auth screens.
 *
 * These values are serialized as CSS custom property declarations and injected
 * into the <head> of every auth page for this tenant.
 *
 * Design contract:
 *   - Brand colors (accent, backgrounds, borders, text) -- tenant-customizable
 *   - Functional colors (error, success, warning states) -- fixed in base CSS
 *   - Structural layout (spacing, typography scale) -- fixed in base CSS
 *
 * Token naming: follows the --color-* / --radius convention defined in
 * frontend/css/base/tokens.css. Auth CSS uses these exact names via var().
 */
data class TenantTheme(
    val accentColor: String = "#1FBCFF",
    val accentHoverColor: String = "#0AAEE8",
    val accentForeground: String = "#05080a",
    val bgDeep: String = "#0C0C0E",
    val bgCard: String = "#1E1E24",
    val bgInput: String = "#2A2A32",
    val borderColor: String = "#2E2E36",
    val borderRadius: String = "8px",
    val textPrimary: String = "#EDEDEF",
    val textMuted: String = "#6B6B75",
    val logoUrl: String? = null,
    val faviconUrl: String? = null,
) {
    /**
     * Emits a CSS :root block injected as an inline <style> tag before the auth
     * stylesheet link in every auth page. The auth CSS (kotauth-auth.css) uses
     * var(--token) throughout and has no :root defaults of its own -- these
     * injected values are the sole source of truth for the tenant's visual identity.
     *
     * Token names match the --color-* convention used in frontend/css/auth/.css.
     * The admin console uses tokens.css (hardcoded defaults) and is not themed.
     */
    fun toCssVars(): String =
        buildString {
            appendLine(":root {")
            appendLine("  --color-accent:       $accentColor;")
            appendLine("  --color-accent-hover: $accentHoverColor;")
            appendLine("  --color-accent-fg:    $accentForeground;")
            appendLine("  --color-bg:           $bgDeep;")
            appendLine("  --color-card:         $bgCard;")
            appendLine("  --color-input:        $bgInput;")
            appendLine("  --color-border:       $borderColor;")
            appendLine("  --color-text:         $textPrimary;")
            appendLine("  --color-muted:        $textMuted;")
            appendLine("  --radius:             $borderRadius;")
            append("}")
        }

    companion object {
        /** KotAuth default dark theme. */
        val DEFAULT =
            TenantTheme(
                accentColor = "#1FBCFF",
                accentHoverColor = "#0AAEE8",
                accentForeground = "#05080a",
                bgDeep = "#0C0C0E",
                bgCard = "#1E1E24",
                bgInput = "#2A2A32",
                borderColor = "#2E2E36",
                borderRadius = "8px",
                textPrimary = "#EDEDEF",
                textMuted = "#6B6B75",
            )

        /** A light theme preset for tenants that want a clean white login page. */
        val LIGHT =
            TenantTheme(
                accentColor = "#0A6EBD",
                accentHoverColor = "#085FA3",
                accentForeground = "#FFFFFF",
                bgDeep = "#F4F5F7",
                bgCard = "#FFFFFF",
                bgInput = "#F0F1F3",
                borderColor = "#E0E1E4",
                borderRadius = "8px",
                textPrimary = "#111114",
                textMuted = "#7A7A85",
            )

        /** Minimal monochrome theme with sharp corners. */
        val SIMPLE =
            TenantTheme(
                accentColor = "#111114",
                accentHoverColor = "#333336",
                accentForeground = "#FFFFFF",
                bgDeep = "#FFFFFF",
                bgCard = "#FAFAFA",
                bgInput = "#F4F4F6",
                borderColor = "#DDDDE0",
                borderRadius = "0px",
                textPrimary = "#111114",
                textMuted = "#6B6B75",
            )
    }
}
