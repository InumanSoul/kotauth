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
    val accentHoverColor: String = "#0ea5d9",
    val bgDeep: String = "#09090b",
    val bgCard: String = "#18181b",
    val bgInput: String = "#27272a",
    val borderColor: String = "#3f3f46",
    val borderRadius: String = "8px",
    val textPrimary: String = "#fafafa",
    val textMuted: String = "#a1a1aa",
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
        /**  KotAuth default dark theme.*/
        val DEFAULT =
            TenantTheme(
                accentColor = "#1FBCFF",
                accentHoverColor = "#0ea5d9",
                bgDeep = "#09090b",
                bgCard = "#18181b",
                bgInput = "#27272a",
                borderColor = "#3f3f46",
                borderRadius = "0px",
                textPrimary = "#fafafa",
                textMuted = "#a1a1aa",
            )

        /** A light theme preset for tenants that want a clean white login page. */
        val LIGHT =
            TenantTheme(
                accentColor = "#0ea5d9",
                accentHoverColor = "#0284c7",
                bgDeep = "#f8fafc",
                bgCard = "#ffffff",
                bgInput = "#f1f5f9",
                borderColor = "#e2e8f0",
                borderRadius = "0px",
                textPrimary = "#0f172a",
                textMuted = "#64748b",
            )

        // Same as LIGHT but with rounded corners.
        val SIMPLE =
            TenantTheme(
                accentColor = "#212121",
                accentHoverColor = "#000000",
                bgDeep = "#fafafa",
                bgCard = "#ffffff",
                bgInput = "#f1f5f9",
                borderColor = "#e2e8f0",
                borderRadius = "8px",
                textPrimary = "#0f172a",
                textMuted = "#64748b",
            )
    }
}
