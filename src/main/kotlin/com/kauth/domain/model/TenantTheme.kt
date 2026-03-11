package com.kauth.domain.model

/**
 * Value object holding the visual identity of a tenant's auth screens.
 *
 * These values are serialized as CSS custom property declarations and injected
 * into the <head> of every auth page for this tenant. Changing a theme means
 * changing a row in the database — no recompile, no redeploy.
 *
 * Design contract:
 *   - Brand colors (accent, backgrounds, borders, text) → tenant-customizable
 *   - Functional colors (error, success, warning states) → fixed in base CSS
 *   - Structural layout (spacing, typography scale) → fixed in base CSS
 *
 * This keeps theming approachable: an admin changes 6–8 values to fully
 * white-label their login page, not 60.
 */
data class TenantTheme(
    // Brand colors
    val accentColor: String       = "#bb86fc",
    val accentHoverColor: String  = "#9965f4",
    // Surface colors
    val bgDeep: String            = "#0f0f13",
    val bgCard: String            = "#1a1a24",
    val bgInput: String           = "#252532",
    // Structure
    val borderColor: String       = "#2e2e3e",
    val borderRadius: String      = "8px",
    // Text
    val textPrimary: String       = "#e8e8f0",
    val textMuted: String         = "#6b6b80",
    // Brand identity
    val logoUrl: String?          = null,
    val faviconUrl: String?       = null
) {
    /**
     * Emits a CSS :root block that overrides the base stylesheet variables.
     * Injected as an inline <style> tag before the base CSS <link> in every auth page.
     *
     * The base CSS file uses var(--token) throughout — no hardcoded colors.
     * This block is the single source of truth for the tenant's visual identity.
     */
    fun toCssVars(): String = buildString {
        appendLine(":root {")
        appendLine("  --accent:       $accentColor;")
        appendLine("  --accent-hover: $accentHoverColor;")
        appendLine("  --bg-deep:      $bgDeep;")
        appendLine("  --bg-card:      $bgCard;")
        appendLine("  --bg-input:     $bgInput;")
        appendLine("  --border:       $borderColor;")
        appendLine("  --radius:       $borderRadius;")
        appendLine("  --text:         $textPrimary;")
        appendLine("  --muted:        $textMuted;")
        append("}")
    }

    companion object {
        /** KotAuth default dark theme — also the master tenant default. */
        val DEFAULT = TenantTheme()

        /** A light theme preset for tenants that want a clean white login page. */
        val LIGHT = TenantTheme(
            accentColor      = "#7c3aed",
            accentHoverColor = "#6d28d9",
            bgDeep           = "#f8fafc",
            bgCard           = "#ffffff",
            bgInput          = "#f1f5f9",
            borderColor      = "#e2e8f0",
            borderRadius     = "8px",
            textPrimary      = "#0f172a",
            textMuted        = "#64748b"
        )
    }
}
