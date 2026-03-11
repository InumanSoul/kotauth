# KotAuth Theming System

## Overview

KotAuth's theming system allows each tenant to fully white-label their auth screens
(login, register, forgot password, etc.) from the database — no recompile, no redeploy,
no build pipeline.

The design contract is intentionally narrow: **tenants control brand and surface colors**.
Functional colors (error, success, warning states) and structural layout (spacing,
typography scale, responsive breakpoints) are fixed. This prevents a misconfigured theme
from making error messages invisible or breaking usability.

---

## Architecture

### How it works (end to end)

```
Database (tenants table)
    ↓ theme_* columns read by PostgresTenantRepository
TenantTheme value object (domain/model/TenantTheme.kt)
    ↓ .toCssVars() called by AuthView / AdminView
Inline <style>:root { --accent: ...; ... }</style>  ← injected into <head>
    ↓ read by
/static/kotauth-auth.css  (or kotauth-admin.css)
    ↓ uses var(--token) exclusively
Browser renders themed page
```

### Key design decisions

**1. CSS custom properties, not Tailwind**

Tailwind generates static CSS at build time. KotAuth's theming requirement is
runtime-dynamic — each tenant can have different colors. CSS custom properties
(`var(--token)`) are the native browser solution for this. No build step, no Node.js,
no CDN dependency.

**2. Theme variables injected before the base stylesheet**

The inline `<style>:root { }` block is placed in `<head>` *before* the `<link>` to the
base CSS. The CSS file never defines `:root` defaults — it only uses `var(--token)`.
This means:
- There is exactly one source of truth for each variable value
- No CSS cascade conflicts
- The browser resolves `var(--accent)` to whatever is in the preceding `:root` block

```html
<head>
  <!-- 1. Theme variables (tenant-specific, or DEFAULT) -->
  <style>:root { --accent: #3b82f6; --bg-deep: #f8fafc; ... }</style>

  <!-- 2. Base stylesheet — reads var(--token) throughout -->
  <link rel="stylesheet" href="/static/kotauth-auth.css">
</head>
```

**3. Functional colors are hardcoded in CSS**

Error, success, and warning state colors are not CSS variables — they are fixed values
in the base CSS files. The reasoning: a tenant picking colors should not accidentally
make error feedback illegible. These can be made configurable in a future "advanced theme"
tier, but are intentionally out of scope for the base theming API.

**4. Admin console uses a fixed theme**

The admin console always renders with `TenantTheme.DEFAULT` (KotAuth's dark purple
brand). The architecture is identical to auth pages — the `adminHead()` function still
calls `toCssVars()` — so making the admin console themeable in the future is a one-line
change. It was a deliberate choice to not expose admin theming in Phase 1.

**5. Static files are served by Ktor**

CSS lives in `src/main/resources/static/` and is served at `/static/*` by Ktor's
`staticResources("/static", "static")`. This gives the files standard HTTP caching
headers (ETag, Last-Modified) automatically. A CDN can be placed in front of these
paths with no application changes.

---

## Token Reference

These tokens are the full theming contract between `TenantTheme.toCssVars()` and the
CSS files. Adding a new token requires changes in all three places.

| CSS Variable      | TenantTheme field    | Default (dark)  | Purpose                                    |
|-------------------|---------------------|-----------------|--------------------------------------------|
| `--accent`        | `accentColor`       | `#bb86fc`       | Buttons, links, focus rings, brand text    |
| `--accent-hover`  | `accentHoverColor`  | `#9965f4`       | Hover state for accent elements            |
| `--bg-deep`       | `bgDeep`            | `#0f0f13`       | Page / body background                     |
| `--bg-card`       | `bgCard`            | `#1a1a24`       | Card and panel surfaces                    |
| `--bg-input`      | `bgInput`           | `#252532`       | Form input backgrounds                     |
| `--border`        | `borderColor`       | `#2e2e3e`       | All border and divider colors              |
| `--radius`        | `borderRadius`      | `8px`           | Border radius for cards and containers     |
| `--text`          | `textPrimary`       | `#e8e8f0`       | Primary body text                          |
| `--muted`         | `textMuted`         | `#6b6b80`       | Labels, placeholders, secondary text       |

**Non-CSS fields** (handled in the view layer, not via CSS variables):

| TenantTheme field | Purpose                                                       |
|-------------------|---------------------------------------------------------------|
| `logoUrl`         | Replaces the "KotAuth" text brand with a tenant's logo image  |
| `faviconUrl`      | Sets the browser tab icon for tenant auth pages               |

---

## Built-in Presets

Two presets are defined as companion objects on `TenantTheme`:

```kotlin
TenantTheme.DEFAULT  // KotAuth dark purple — used by master tenant and new tenants
TenantTheme.LIGHT    // Clean white surfaces with purple accent — good starting point
                     // for B2B SaaS tenants that prefer a light UI
```

Presets are not stored in the database — they are Kotlin constants used to seed new
tenants or reset a theme to a known state. Future work could expose a preset selector
in the admin console's Branding tab.

---

## Database Schema

Theme values are stored as individual `VARCHAR` columns on the `tenants` table,
added by the V3 migration. Columns use `NOT NULL DEFAULT` matching `TenantTheme.DEFAULT`,
so existing rows (including `master`) inherit the dark theme without a data backfill.

```sql
-- V3__add_tenant_theme.sql (excerpt)
ALTER TABLE tenants
    ADD COLUMN theme_accent_color  VARCHAR(30) NOT NULL DEFAULT '#bb86fc',
    ADD COLUMN theme_accent_hover  VARCHAR(30) NOT NULL DEFAULT '#9965f4',
    ADD COLUMN theme_bg_deep       VARCHAR(30) NOT NULL DEFAULT '#0f0f13',
    -- ... (see full migration for all columns)
    ADD COLUMN theme_logo_url      VARCHAR(500),
    ADD COLUMN theme_favicon_url   VARCHAR(500);
```

### Why individual columns, not a JSONB blob?

- Individual columns can be validated at the DB level (`CHECK` constraints on format)
- Partial updates are a plain `UPDATE tenants SET theme_accent_color = ? WHERE slug = ?`
- No deserialization code path — Exposed reads them directly
- JSONB would be better if the theme schema was highly variable or deeply nested;
  our token count is small and stable

---

## Static Files

| File                              | Serves               | Used by           |
|-----------------------------------|----------------------|-------------------|
| `src/main/resources/static/kotauth-auth.css`  | `/static/kotauth-auth.css`  | `AuthView`        |
| `src/main/resources/static/kotauth-admin.css` | `/static/kotauth-admin.css` | `AdminView`       |

Both files use `var(--token)` exclusively for brand and surface colors.
Functional colors (error/success/warn) are hardcoded values — intentionally not tokens.

---

## How to Apply a Theme

### From code (e.g., seeding a tenant with a custom theme)

```kotlin
val myTheme = TenantTheme(
    accentColor      = "#2563eb",   // blue brand
    accentHoverColor = "#1d4ed8",
    bgDeep           = "#f8fafc",   // light background
    bgCard           = "#ffffff",
    bgInput          = "#f1f5f9",
    borderColor      = "#e2e8f0",
    textPrimary      = "#0f172a",
    textMuted        = "#64748b",
    borderRadius     = "6px",
    logoUrl          = "https://cdn.acme.com/logo.png"
)
```

### From the admin console (current MVP)

The Create Tenant form exposes two theme fields: accent color (color picker) and logo URL.
Full theme editing is planned for the Branding tab in Phase 2.

---

## Future Enhancements

### Phase 2 — Branding tab in tenant detail

Add a "Branding" tab to the admin console's tenant detail page with:
- Color pickers for all token values
- Live preview iframe showing the login page with the current theme applied
- Preset selector (Dark / Light / Custom)
- Logo and favicon upload (to a configured S3/object-storage bucket)
- "Reset to default" button

Implementation: `PUT /admin/tenants/{slug}/theme` handler, new `updateTheme()` method
on `TenantRepository`, `V4__...sql` migration is not needed (V3 already added the columns).

### Phase 2 — Theme caching

Every auth page render currently fires a DB read for the tenant's theme. This is
acceptable for MVP but should be cached once traffic warrants it. Candidate approaches:

- **Request-scoped cache** — resolve the tenant once per request in the route, pass the
  `Tenant` object (not just the slug) down to `AuthService`. Avoids the double-query
  that currently happens (one in the route for theme, one inside `AuthService` for
  policies). This is the right first step.
- **In-process TTL cache** — a Guava or Caffeine cache on `PostgresTenantRepository`
  keyed by slug, 60-second TTL. Zero infrastructure. Acceptable for most deployments.
- **Redis** — only warranted for multi-instance deployments with very high auth traffic.

The port interface (`TenantRepository`) makes all three approaches a swap-in behind
the same interface. Application code changes nothing.

### Phase 3 — Per-client theme override

Today, theme is per-tenant. Keycloak supports per-client login themes. This would mean:

1. `clients` table gets `theme_override` JSONB column (nullable — null means inherit tenant theme)
2. Auth routes receive `?client_id=...` query parameter (standard OAuth flow)
3. Routes resolve: `client.themeOverride ?: tenant.theme`
4. `TenantTheme` gets a `merge(override: TenantTheme?)` function that applies only
   non-null override fields

### Phase 3 — Custom CSS injection

Some enterprise customers will want to go beyond the token system (custom fonts,
layout tweaks, animation). A safe approach:
- `tenants` table gets a `theme_custom_css TEXT` column
- Injected as a third `<style>` block in `<head>`, after the base CSS link
- Sanitized server-side (strip `<script>`, `javascript:`, external `url()` references)
- Scoped to a tenant-specific CSS class on `<body>` to prevent bleed-over

### Phase 4 — Font customization

Currently the font is hardcoded to `'Inter', system-ui, ...`. Exposing font-family
as a token requires:
- Adding `--font` to `TenantTheme.toCssVars()` and the CSS files
- A Google Fonts / Bunny Fonts URL field on `TenantTheme`
- Injecting `<link href="https://fonts.googleapis.com/...">` in `<head>` when set
- Performance consideration: self-hosted fonts are preferable for auth pages
  (avoids a third-party DNS lookup on the critical path)

---

## What KotAuth Does NOT Do

- **Runtime CSS compilation** — we do not run PostCSS, Sass, or Tailwind at request time
- **Client-side theming** — no JavaScript reads the theme; it is fully server-rendered
- **Theme inheritance chains** — themes do not cascade between tenants
- **User-level themes** — theming is per-tenant (and later per-client), never per-user
