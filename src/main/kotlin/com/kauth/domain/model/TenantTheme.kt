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
    // Brand colors — Zinc-dark palette, brand cyan accent
    val accentColor: String       = "#1FBCFF",
    val accentHoverColor: String  = "#0ea5d9",
    // Surface colors — shadcn Zinc-dark token set
    val bgDeep: String            = "#09090b",
    val bgCard: String            = "#18181b",
    val bgInput: String           = "#27272a",
    // Structure
    val borderColor: String       = "#3f3f46",
    val borderRadius: String      = "8px",
    // Text
    val textPrimary: String       = "#fafafa",
    val textMuted: String         = "#a1a1aa",
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
        /**
         * KotAuth default dark theme — Zinc/dark palette matching the admin console design.
         * Accent: brand cyan #1FBCFF (not purple — updated to match design system).
         * Surfaces follow the shadcn Zinc-dark token set.
         */
        val DEFAULT = TenantTheme(
            accentColor      = "#1FBCFF",
            accentHoverColor = "#0ea5d9",
            bgDeep           = "#09090b",
            bgCard           = "#18181b",
            bgInput          = "#27272a",
            borderColor      = "#3f3f46",
            borderRadius     = "0px",
            textPrimary      = "#fafafa",
            textMuted        = "#a1a1aa"
        )

        /** A light theme preset for tenants that want a clean white login page. */
        val LIGHT = TenantTheme(
            accentColor      = "#0ea5d9",
            accentHoverColor = "#0284c7",
            bgDeep           = "#f8fafc",
            bgCard           = "#ffffff",
            bgInput          = "#f1f5f9",
            borderColor      = "#e2e8f0",
            borderRadius     = "0px",
            textPrimary      = "#0f172a",
            textMuted        = "#64748b"
        )
    }
}
