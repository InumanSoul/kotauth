# Frontend Architecture

CSS, JavaScript, and htmx patterns for KotAuth's admin UI. Component-scoped CSS
source files compiled to two minified bundles at build time using LightningCSS.
CSP-safe JavaScript interactions via `data-*` attributes. Surgical htmx for
in-page state transitions. No Node.js in the final Docker image.

---

## Table of Contents

1. [Two-Bundle Strategy](#two-bundle-strategy)
2. [Folder Structure](#folder-structure)
3. [Tooling](#tooling)
4. [Design Tokens](#design-tokens)
5. [Theming Model](#theming-model)
6. [BEM Convention](#bem-convention)
7. [File Responsibilities](#file-responsibilities)
8. [Component Reference](#component-reference)
9. [Writing New Components](#writing-new-components)
10. [Kotlin Integration](#kotlin-integration)
11. [Build Pipeline](#build-pipeline)
12. [Dockerfile Integration](#dockerfile-integration)
13. [Development Workflow](#development-workflow)
14. [JavaScript — settings.js](#javascript--settingsjs)
15. [htmx Patterns](#htmx-patterns)
16. [Rules and Constraints](#rules-and-constraints)

---

## Two-Bundle Strategy

KotAuth compiles two separate CSS bundles instead of one monolithic file.

| Bundle | Entry point | Output | Audience |
|---|---|---|---|
| Admin | `frontend/css/index-admin.css` | `static/kotauth-admin.css` | Admin console (`/admin/**`) |
| Auth  | `frontend/css/index-auth.css`  | `static/kotauth-auth.css`  | Auth pages (`/login`, `/register`, etc.) |

**Why separate bundles?**

- Auth pages don't need shell layout, rail nav, or data table CSS — they just need a centered card.
- Admin is visually fixed (KotAuth dark theme). Auth is tenant-customizable (white-label).
- Smaller bundles = faster first-paint on the auth flow, which is the end-user critical path.
- The theming architecture is fundamentally different between the two (see [Theming Model](#theming-model)).

---

## Folder Structure

```
project-root/
│
├── frontend/                          ← CSS source — never served directly
│   └── css/
│       ├── index-admin.css            ← Admin bundle entry point (imports only)
│       ├── index-auth.css             ← Auth bundle entry point (imports only)
│       │
│       ├── base/
│       │   ├── tokens.css             ← Design tokens (admin bundle only)
│       │   ├── reset.css              ← Box-sizing and base body rules (both bundles)
│       │   └── typography.css         ← Font stack, links, code (admin bundle only)
│       │
│       ├── layout/                    ← Admin bundle only
│       │   ├── shell.css              ← .shell, .shell-body, body overflow
│       │   ├── topbar.css             ← .shell-topbar, .ws-dropdown, .topbar-*
│       │   ├── rail.css               ← .rail, .rail-item, .rail-brand
│       │   ├── sidebar.css            ← .ctx-panel, .ctx-item, .ctx-empty
│       │   └── content.css            ← .main, .content, .page-header, .breadcrumb
│       │
│       ├── components/                ← Admin bundle only
│       │   ├── button.css             ← .btn, .btn--primary/ghost/danger/warning/sm/icon
│       │   ├── badge.css              ← .badge, .badge--active/inactive/confidential/danger
│       │   ├── alert.css              ← .alert, .alert-error, .alert-success, .alert-warn
│       │   ├── card.css               ← .card, .card-body, .card-title (legacy)
│       │   ├── form.css               ← input, select, textarea, label, .field (legacy global)
│       │   ├── stat-card.css          ← .stat-grid, .stat-card, .stat-label, .stat-value
│       │   ├── table.css              ← .data-table, .data-table__id/name/actions (BEM)
│       │   ├── empty-state.css        ← .empty-state, .empty-state__icon/title/desc/cta (BEM)
│       │   ├── ov-card.css            ← .ov-card, .ov-card__section-label/row/label/value (BEM)
│       │   ├── notice.css             ← .notice, .notice--success/error (BEM)
│       │   ├── copy-btn.css           ← .copy-btn hover/copied state (extends btn--ghost btn--icon)
│       │   ├── divider.css            ← .divider horizontal separator
│       │   ├── danger-zone.css        ← .danger-zone card for destructive actions
│       │   ├── section.css            ← .section, .section__header/title (legacy)
│       │   ├── toggle.css             ← .toggle switch component (BEM)
│       │   ├── check-row.css          ← .check-row checkbox row (BEM)
│       │   ├── radio-row.css          ← .radio-row radio button row (BEM)
│       │   └── chip-grid.css          ← .chip-grid, .scope-chip multi-select chips (BEM)
│       │
│       ├── pages/                     ← Page-specific styles
│       │   ├── workspace-overview.css ← Workspace overview page
│       │   ├── app-detail.css         ← Application detail page
│       │   ├── user-detail.css        ← .user-header, .lock-icon, .edit-row/actions (BEM)
│       │   ├── branding.css           ← Branding settings page
│       │   └── settings.css           ← .toggle-row, .provider-header, .copy-field,
│       │                                 .key-table, .setup-row (settings pages)
│       │
│       ├── admin/                     ← Admin-specific pages (legacy)
│       │   ├── login.css              ← .login-shell, .brand-mark, .login-card
│       │   ├── form-card.css          ← .form-card, .form-section-title, .checkbox-row
│       │   └── welcome.css            ← Admin welcome page
│       │
│       └── auth/                      ← Auth bundle only
│           ├── shell.css              ← body centered layout
│           ├── brand.css              ← .brand, .brand-logo, .brand-name, .brand-tagline
│           ├── card.css               ← .card, .card-title, .card-subtitle (auth variant)
│           ├── form.css               ← input, label, .field (auth sizing)
│           ├── button.css             ← .btn (full-width auth primary)
│           ├── alert.css              ← .alert, .alert-error, .alert-success
│           ├── social.css             ← .social-divider, .social-buttons, .btn-social
│           └── misc.css               ← .footer-link, .divider
│
└── src/
    └── main/
        └── resources/
            └── static/
                ├── kotauth-admin.css  ← Compiled output — never edit by hand
                └── kotauth-auth.css   ← Compiled output — never edit by hand
```

**Rule:** `frontend/` is source. `resources/static/` is dist.
Nothing in `frontend/` is referenced directly from Kotlin or served to browsers.
Nothing in `resources/static/` is hand-edited.

---

## Tooling

### LightningCSS

A Rust-based CSS compiler distributed as an npm package (`lightningcss-cli`). It handles:

- `@import` resolution and bundling across all source files
- Vendor prefix injection
- Dead code elimination
- Minification

The CLI is installed locally into `frontend/node_modules/` via the `frontend/package.json` manifest. **Node.js is only needed at build time** — it is not present in the runtime Docker image.

### Installation

No manual installation required for local development or Docker builds. The Gradle `installCssDeps` task runs `npm ci --prefix frontend` automatically before any CSS compilation task.

**Prerequisites:** Node.js (≥ 12) and npm must be available on the developer's machine and in any CI environment that runs `./gradlew build` locally (not needed for Docker builds — the Dockerfile handles it).

The `lightningcss-cli` version is pinned in `frontend/package.json` and locked in `frontend/package-lock.json` for reproducible builds across all environments.

### Build Commands

```bash
# Admin bundle
./node_modules/.bin/lightningcss --bundle --minify --targets '>= 0.5%' \
  frontend/css/index-admin.css \
  -o src/main/resources/static/kotauth-admin.css

# Auth bundle
lightningcss --bundle --minify --targets '>= 0.5%' \
  frontend/css/index-auth.css \
  -o src/main/resources/static/kotauth-auth.css
```

Or use Gradle (which runs both automatically before packaging):

```bash
./gradlew compileCssAdmin compileCssAuth
# or just:
./gradlew build  # both CSS tasks run automatically before processResources
```

---

## Design Tokens

All tokens are defined in `frontend/css/base/tokens.css` and are used **exclusively** in the admin bundle. Token names follow the `--color-*` convention.

```css
/* frontend/css/base/tokens.css */

:root {
  /* Backgrounds */
  --color-bg:           #0c0c0e;
  --color-surface:      #131316;
  --color-card:         #18181c;
  --color-input:        #27272a;
  --color-row-hover:    rgba(255, 255, 255, 0.025);

  /* Borders */
  --color-border:       rgba(255, 255, 255, 0.07);
  --color-border-md:    rgba(255, 255, 255, 0.11);
  --color-border-em:    rgba(255, 255, 255, 0.18);

  /* Text */
  --color-text:         #ededef;
  --color-muted:        #6b6b75;
  --color-subtle:       #3d3d45;

  /* Accent — brand blue */
  --color-accent:         #1fbcff;
  --color-accent-hover:   #0ea5d9;
  --color-accent-bg:      rgba(31, 188, 255, 0.08);
  --color-accent-border:  rgba(31, 188, 255, 0.22);

  /* Semantic colors (green / red / amber) each have -bg and -border variants */

  /* Typography */
  --font-sans: 'Inter', system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  --font-mono: 'Inconsolata', 'SF Mono', 'Fira Code', 'Cascadia Code', monospace;

  /* Spacing: --space-1 (4px) through --space-10 (40px) */
  /* Radius:  --radius-sm (4px) through --radius-full (9999px) */
  /* Border shorthands: --border, --border-md, --border-em */
}
```

**Reference table:**

| Token | Value | Use |
|---|---|---|
| `--color-bg` | `#0c0c0e` | Page background |
| `--color-surface` | `#131316` | Topbar, rail, sidebar |
| `--color-card` | `#18181c` | Cards, panels |
| `--color-text` | `#ededef` | Primary text |
| `--color-muted` | `#6b6b75` | Labels, secondary |
| `--color-subtle` | `#3d3d45` | Hints, empty, disabled |
| `--color-accent` | `#1fbcff` | Brand blue, links, focus |
| `--color-green` | `#4ade80` | Active, success |
| `--color-red` | `#f87171` | Danger, error |
| `--color-amber` | `#fbbf24` | Warning, reversible |

---

## Theming Model

Auth pages and the admin console use **completely different theming approaches**.

### Admin console — fixed theme

The admin console always renders in KotAuth's default dark theme. Design tokens are hardcoded in `base/tokens.css` and are not overrideable at runtime.

### Auth pages — tenant white-label

Auth pages (`/login`, `/register`, `/reset-password`, etc.) are fully white-labeled per tenant. The visual identity is stored in the database as a `TenantTheme` record.

**How it works:**

1. A request arrives at an auth route.
2. The route loads the `Tenant` from the database, which includes a `TenantTheme`.
3. `AuthView.authHead()` calls `theme.toCssVars()` and injects the result as an inline `<style>` block in the page `<head>`, **before** the stylesheet `<link>`.
4. The auth stylesheet (`kotauth-auth.css`) uses `var(--color-accent)`, `var(--color-bg)`, etc. — it has no `:root {}` defaults of its own.
5. The injected variables are the only definitions — they win by default.

```html
<!-- Rendered in <head> — example with a custom brand -->
<style>
:root {
  --color-accent:       #e040fb;
  --color-bg:           #0f0015;
  --color-card:         #1a0d2e;
  /* ... all 9 tenant tokens ... */
}
</style>
<link rel="stylesheet" href="/static/kotauth-auth.css">
```

**Changing a tenant's visual identity requires only a database update — no recompile, no redeploy.**

### Token names emitted by `TenantTheme.toCssVars()`

| CSS variable | `TenantTheme` field | Purpose |
|---|---|---|
| `--color-accent` | `accentColor` | Brand color — buttons, links, focus |
| `--color-accent-hover` | `accentHoverColor` | Button hover state |
| `--color-bg` | `bgDeep` | Page background |
| `--color-card` | `bgCard` | Card / panel surface |
| `--color-input` | `bgInput` | Form input background |
| `--color-border` | `borderColor` | Border color |
| `--color-text` | `textPrimary` | Primary text |
| `--color-muted` | `textMuted` | Labels, secondary text |
| `--radius` | `borderRadius` | Border radius (single value, auth only) |

Auth CSS uses `--radius` for cards and `calc(var(--radius) - 2px)` for inputs and buttons.
Admin CSS uses fixed scale tokens (`--radius-md`, `--radius-lg`, etc.) from `tokens.css`.

---

## BEM Convention

All class names follow **Block__Element--Modifier** notation. This is the contract between CSS files and Kotlin view code.

### Syntax

```
.block                     Block — standalone component
.block__element            Element — child part of a block
.block--modifier           Modifier — variant of a block or element
.block__element--modifier  Element variant
```

### Rules

**No nesting in CSS.** Every selector is flat. Specificity stays at 0-1-0 for every rule.

```css
/* Correct */
.ov-card__row { ... }
.ov-card__row:hover { ... }

/* Wrong */
.ov-card .ov-card__row { ... }
```

**Modifier always alongside base class.**

```html
<!-- Correct -->
<span class="badge badge--active">Active</span>

<!-- Wrong — modifier without base -->
<span class="badge--active">Active</span>
```

**State via modifier class, never inline style.**

```html
<!-- Correct -->
<span class="badge ${if (active) "badge-active" else "badge-disabled"}">...</span>

<!-- Wrong -->
<span class="badge" style="color: green;">...</span>
```

### Existing class naming

Most existing classes in the admin console use a single-dash modifier convention (`.btn-ghost`, `.btn-danger`, `.badge-active`) for historical reasons. New components should follow strict BEM double-dash (`.btn--ghost`, `.badge--active`). `ov-card.css` is the reference example for all new work.

---

## File Responsibilities

| File | Bundle | Responsibility |
|---|---|---|
| `base/tokens.css` | Admin | All CSS custom properties — single source of truth for admin design values |
| `base/reset.css` | Both | Box-sizing reset, base body rules |
| `base/typography.css` | Admin | Font stack, link color, code font |
| `layout/shell.css` | Admin | `.shell`, `.shell-body`, body overflow |
| `layout/topbar.css` | Admin | Topbar, workspace switcher dropdown, search, avatar |
| `layout/rail.css` | Admin | Icon navigation rail |
| `layout/sidebar.css` | Admin | Context panel, section titles, nav items |
| `layout/content.css` | Admin | Main content area, `.page-header`, `.breadcrumb` |
| `components/button.css` | Admin | `.btn` + BEM modifiers (`--primary`, `--ghost`, `--danger`, `--warning`, `--sm`, `--icon`) |
| `components/badge.css` | Admin | `.badge` + BEM modifiers (`--active`, `--inactive`, `--confidential`, `--danger`) |
| `components/alert.css` | Admin | Inline feedback banners (legacy) |
| `components/card.css` | Admin | Generic card containers (legacy) |
| `components/form.css` | Admin | Global input/select/label rules (legacy — causes bleed, see note below) |
| `components/stat-card.css` | Admin | Metric stat grid |
| `components/table.css` | Admin | `.data-table` with `__id`, `__name`, `__email`, `__actions` |
| `components/empty-state.css` | Admin | `.empty-state` with `__icon` (SVG sized), `__title`, `__desc`, `__cta` |
| `components/ov-card.css` | Admin | `.ov-card` with `__section-label`, `__row`, `__label`, `__value` + modifiers |
| `components/notice.css` | Admin | `.notice` with `--success`, `--error` modifiers |
| `components/copy-btn.css` | Admin | `.copy-btn` hover/copied state (used with `btn--ghost btn--icon`) |
| `components/divider.css` | Admin | `.divider` horizontal separator |
| `components/danger-zone.css` | Admin | `.danger-zone` destructive action card |
| `components/section.css` | Admin | `.section`, `.section__header`, `.section__title` (legacy) |
| `components/toggle.css` | Admin | `.toggle` switch component |
| `components/check-row.css` | Admin | `.check-row` labeled checkbox with global rule resets |
| `components/radio-row.css` | Admin | `.radio-row` labeled radio with global rule resets |
| `components/chip-grid.css` | Admin | `.chip-grid`, `.scope-chip` multi-select chip grid |
| `pages/workspace-overview.css` | Admin | Workspace overview page |
| `pages/app-detail.css` | Admin | Application detail page |
| `pages/user-detail.css` | Admin | `.user-header`, `.lock-icon`, `.edit-row`, `.edit-actions` |
| `pages/branding.css` | Admin | Branding settings (preset-group, color-grid) |
| `pages/settings.css` | Admin | `.toggle-row`, `.provider-header`, `.copy-field`, `.key-table`, `.setup-row` |
| `admin/login.css` | Admin | Admin login page layout |
| `admin/form-card.css` | Admin | Settings and edit form cards (legacy) |
| `admin/welcome.css` | Admin | Admin welcome page |
| `auth/shell.css` | Auth | Body centered layout |
| `auth/brand.css` | Auth | Tenant brand header |
| `auth/card.css` | Auth | Auth card with shadow |
| `auth/form.css` | Auth | Auth form inputs (larger sizing) |
| `auth/button.css` | Auth | Full-width primary button |
| `auth/alert.css` | Auth | Auth feedback banners (hardcoded functional colors) |
| `auth/social.css` | Auth | Social login divider and provider buttons |
| `auth/misc.css` | Auth | Footer links and dividers |

> **Known issue — `components/form.css` global bleed:** This file has global `label { text-transform: uppercase }` and `input { width: 100%; padding: 0.6rem }` rules that infect BEM components using `<label>` and `<input>` elements. BEM components (check-row, radio-row, toggle, scope-chip) include explicit resets to counteract this. A future cleanup should scope these rules to `.field label` or similar.

---

## Component Reference

### `ov-card` — primary card component

The ov-card is the standard container for detail pages, settings forms, and any key-value layout. Cards stack with 20px gap via `.ov-card + .ov-card { margin-top: 20px }`.

```css
.ov-card                          /* card container */
.ov-card__section-label           /* flex header row: title left, actions right */
.ov-card__section-label--danger   /* red-tinted variant for danger zones */
.ov-card__row                     /* grid row: 152px label + 1fr value */
.ov-card__row--stacked            /* vertical: label above value */
.ov-card__row--inherited          /* subtle bg for inherited/readonly values */
.ov-card__label                   /* row label */
.ov-card__value                   /* row value (flex, centered) */
.ov-card__value--mono             /* monospace accent (IDs, keys) */
.ov-card__value--muted            /* secondary text */
.ov-card__value--empty            /* placeholder for unset fields */
.ov-card__actions                 /* footer action bar */
```

Kotlin usage — read-only card:

```kotlin
div("ov-card") {
    div("ov-card__section-label") { +"Profile" }
    div("ov-card__row") {
        span("ov-card__label") { +"Client ID" }
        span("ov-card__value") {
            span("ov-card__value--mono") { +app.clientId }
            copyBtn(app.clientId)
        }
    }
}
```

Kotlin usage — section label with actions:

```kotlin
div("ov-card") {
    div("ov-card__section-label") {
        span { +"Active Sessions" }
        div { /* action buttons go here */ }
    }
    /* table or content */
}
```

### `edit-row` — form row for editable fields

Used inside ov-cards for settings and edit forms. Grid layout matching ov-card__row proportions.

```css
.edit-row                /* grid: 160px label + 1fr field */
.edit-row__label         /* left label column */
.edit-row__field         /* input with border and focus state */
.edit-row__field--mono   /* monospace variant */
.edit-row__field--select /* styled select with chevron background */
.edit-row__hint          /* helper text below field */
.edit-actions            /* save/cancel action bar with top border */
```

### `data-table` — list page table

```css
.data-table              /* full-width bordered table */
.data-table__id          /* accent link in first column */
.data-table__name        /* primary text cell */
.data-table__email       /* secondary text cell */
.data-table__actions     /* right-aligned action buttons */
```

### `key-table` — compact sub-table for detail pages

Used for child resources inside a detail page (composite roles, assigned users, webhook history).

```css
.key-table               /* bordered table with hover rows */
.key-table__name         /* bold primary cell */
.key-table__meta         /* monospace secondary cell */
```

### `empty-state` — empty list placeholder

```css
.empty-state             /* centered card with dashed border */
.empty-state__icon       /* 32×32 icon box — color: var(--color-muted) */
.empty-state__icon svg   /* 16×16 constrained SVG icon */
.empty-state__title      /* short heading */
.empty-state__desc       /* body text, max 260px */
.empty-state__cta        /* optional accent action button */
```

### `chip-grid` — multi-select chips

Used for scope selection (API keys) and event selection (webhooks).

```css
.chip-grid               /* flex-wrap grid of chips */
.chip-grid__header       /* header row with count and All/None buttons */
.chip-grid__count        /* "3 / 12 selected" live counter */
.scope-chip              /* individual checkbox chip */
```

### `copy-field` — monospace value + copy button

Inline field for one-time-visible secrets and callback URLs.

```css
.copy-field              /* flex container with border */
.copy-field__value       /* mono, user-select:all, ellipsis overflow */
.copy-field__btn         /* border-left separator, SVG icon, hover accent */
```

### `copy-btn` — inline copy button

Small icon button using `btn btn--ghost btn--icon copy-btn`. Uses `data-copy` attribute for CSP-safe clipboard access via `settings.js`.

```css
.copy-btn:hover          /* accent border and color */
.copy-btn[data-copied]   /* green check feedback state */
```

### Other BEM components

| Component | File | Purpose |
|---|---|---|
| `.toggle` | `toggle.css` | On/off switch (label + track + thumb) |
| `.check-row` | `check-row.css` | Labeled checkbox with description |
| `.radio-row` | `radio-row.css` | Labeled radio button with description |
| `.notice` | `notice.css` | Alert banner (`--success`, `--error`) |
| `.badge` | `badge.css` | Status pills (`--active`, `--inactive`, `--confidential`, `--danger`) |
| `.page-header` | `content.css` | Page title with breadcrumb and action buttons |
| `.breadcrumb` | `content.css` | Navigation breadcrumb trail |

---

## Writing New Components

### Step 1 — Name the block

```
Good:  session-card, permission-row, token-display, webhook-endpoint
Bad:   green-box, right-panel, big-card, colored-thing
```

### Step 2 — Create the file

Admin component → `frontend/css/components/my-component.css`
Auth component  → `frontend/css/auth/my-component.css`

### Step 3 — Write flat BEM rules, use tokens only

```css
/* frontend/css/components/session-card.css */

.session-card {
  background: var(--color-card);
  border: var(--border-md);
  border-radius: var(--radius-xl);
  padding: var(--space-4);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.session-card__app   { font-size: 13px; font-weight: 500; color: var(--color-text); }
.session-card__meta  { font-size: 11px; color: var(--color-muted); margin-top: 2px; }
.session-card__ip    { font-family: var(--font-mono); font-size: 11px; color: var(--color-subtle); }
.session-card--revoked { opacity: 0.5; }
```

### Step 4 — Register in the entry point

```css
/* In frontend/css/index-admin.css, components section: */
@import "./components/session-card.css";
```

### Step 5 — Run the build

```bash
./gradlew compileCssAdmin
```

---

## Kotlin Integration

Kotlin view files contain class name strings and structural HTML only. Zero visual knowledge — no colors, no pixel values, no style attributes.

```kotlin
// Correct — BEM badge with modifier
span("badge badge--active") {
    span("badge__dot") {}
    +"Active"
}

// Wrong — hardcoded color in Kotlin
div { style = "color: #4ade80; padding: 8px;" }
```

### View Architecture

Views follow a pure function pattern: `data in → HTML out`. Each page has a top-level function (e.g. `userDetailPageImpl`) that returns an `HTML.() -> Unit` lambda. Shared components live in `AdminComponents.kt` as extension functions on `DIV`.

Key helpers in `AdminComponents.kt`:

| Helper | Purpose |
|---|---|
| `ovCard { }` | Wraps content in `.ov-card` |
| `ovRow(label) { }` | Grid row with label + custom value |
| `ovRowMono(label, value, copyable)` | Monospace accent row with optional copy button |
| `ovRowText(label, value)` | Plain text row |
| `ovRowMuted(label, value)` | Secondary/muted row |
| `ovSectionLabel(label)` | Section divider inside an ov-card |
| `breadcrumb(vararg crumbs)` | Breadcrumb trail |
| `primaryLink(href, label, icon)` | Accent link button with SVG icon |
| `emptyState(icon, title, desc)` | Empty list placeholder |
| `copyBtn(text)` | CSP-safe copy-to-clipboard button |
| `postButton(action, label)` | Inline POST form with submit button |
| `dangerZoneCard(title, desc) { }` | Destructive action card |

### SVG Icons

Icons are inline SVGs loaded from `src/main/resources/static/icons/`. Use `inlineSvgIcon(name, ariaLabel)` in any `HTMLTag` context. Icons use `currentColor` for stroke/fill, inheriting from the parent's CSS `color` property.

Available icons: `admin`, `arrow-small`, `arrow-t-r`, `code`, `copy`, `edit`, `external-link`, `globe`, `lock`, `logout`, `open-sm`, `plus`, `pulse`, `rail-*`, `redirect`, `search`, `slug`, `user`, `warning`.

---

## Build Pipeline

Three Gradle tasks handle CSS — all wired to run automatically before `processResources`.

```kotlin
// build.gradle.kts

val lightningCssBin = "frontend/node_modules/.bin/lightningcss"

// Step 1 — install lightningcss-cli from the lockfile (cached after first run)
val installCssDeps = tasks.register<Exec>("installCssDeps") {
    description = "Installs LightningCSS CLI into frontend/node_modules via npm ci"
    group = "build"
    commandLine("npm", "ci", "--prefix", "frontend")
    inputs.files("frontend/package.json", "frontend/package-lock.json")
    outputs.dir("frontend/node_modules")
}

// Step 2a — admin bundle
val compileCssAdmin = tasks.register<Exec>("compileCssAdmin") {
    description = "Compiles the admin bundle (index-admin.css → kotauth-admin.css)"
    group = "build"
    dependsOn(installCssDeps)
    commandLine(lightningCssBin, "--bundle", "--minify", "--targets", ">= 0.5%",
        "frontend/css/index-admin.css", "-o", "src/main/resources/static/kotauth-admin.css")
    inputs.dir("frontend/css")
    outputs.file("src/main/resources/static/kotauth-admin.css")
}

// Step 2b — auth bundle
val compileCssAuth = tasks.register<Exec>("compileCssAuth") {
    description = "Compiles the auth bundle (index-auth.css → kotauth-auth.css)"
    group = "build"
    dependsOn(installCssDeps)
    commandLine(lightningCssBin, "--bundle", "--minify", "--targets", ">= 0.5%",
        "frontend/css/index-auth.css", "-o", "src/main/resources/static/kotauth-auth.css")
    inputs.dir("frontend/css")
    outputs.file("src/main/resources/static/kotauth-auth.css")
}

tasks.named("processResources") {
    dependsOn(compileCssAdmin, compileCssAuth)
}
```

`inputs.dir` / `outputs.file` on each task enables Gradle incremental builds — compilation is skipped when nothing in `frontend/css/` has changed. `installCssDeps` is similarly cached against `package.json` and `package-lock.json`.

---

## Dockerfile Integration

```
Stage 1  css-build     — node:20-slim + npm ci + lightningcss-cli
Stage 2  kotlin-build  — gradle:8-jdk17 (copies CSS from Stage 1, skips all CSS tasks)
Stage 3  runtime       — eclipse-temurin:17-jre, no Node, no build tools (~85 MB total)
```

```dockerfile
# Stage 1: CSS compilation
FROM node:20-slim AS css-build
WORKDIR /build
# Install from lockfile — layer cached until package-lock.json changes
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
# Copy source and compile
COPY frontend/css ./css
RUN ./node_modules/.bin/lightningcss --bundle --minify --targets '>= 0.5%' \
    css/index-admin.css -o /build/kotauth-admin.css
RUN ./node_modules/.bin/lightningcss --bundle --minify --targets '>= 0.5%' \
    css/index-auth.css  -o /build/kotauth-auth.css

# Stage 2: Kotlin build (CSS already compiled — skip all three CSS tasks)
FROM gradle:8-jdk17 AS kotlin-build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
COPY --from=css-build /build/kotauth-admin.css src/main/resources/static/kotauth-admin.css
COPY --from=css-build /build/kotauth-auth.css  src/main/resources/static/kotauth-auth.css
RUN gradle buildFatJar -x installCssDeps -x compileCssAdmin -x compileCssAuth --no-daemon

# Stage 3: Runtime — no Node.js, no npm, no LightningCSS
FROM eclipse-temurin:17-jre
COPY --from=kotlin-build /home/gradle/src/build/libs/*.jar /app/kauth.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/kauth.jar"]
```

---

## Development Workflow

### Prerequisites

Node.js (≥ 12) and npm must be installed on the developer's machine. The first `./gradlew build` call installs `lightningcss-cli` automatically via `npm ci --prefix frontend`.

### Building CSS locally

```bash
./gradlew compileCssAdmin    # admin bundle only
./gradlew compileCssAuth     # auth bundle only
./gradlew compileCssAdmin compileCssAuth  # both
./gradlew build              # full build (includes CSS)
```

### Watch mode

```bash
# Using entr (brew install entr / apt install entr)
# Make sure deps are installed first: ./gradlew installCssDeps
find frontend/css -name '*.css' | entr sh -c \
  'npm run build --prefix frontend'
```

Or directly via npm scripts defined in `frontend/package.json`:

```bash
cd frontend
npm run build          # compile both bundles once
npm run build:admin    # admin bundle only
npm run build:auth     # auth bundle only
npm run watch          # watch mode via entr (requires entr installed)
```

Drop `--minify` in `frontend/package.json` scripts during development for readable DevTools output. Remember to restore it before committing.

---

## JavaScript — `settings.js`

All client-side interactions use a single CSP-safe script: `src/main/resources/static/js/settings.js`. It runs as an IIFE with `'use strict'` and binds behavior exclusively through `data-*` attributes — zero inline event handlers anywhere in the Kotlin views.

### Data attributes

| Attribute | Element | Behavior |
|---|---|---|
| `data-copy="text"` | `<button>` | Copies `text` to clipboard. Shows a checkmark SVG for 1.5s feedback. Sets `[data-copied]` for CSS styling. |
| `data-confirm="message"` | `<button>` | Shows `window.confirm(message)` before the action. Prevents default on cancel. |
| `data-chips-all="gridId"` | `<button>` | Checks all checkboxes in the `#gridId` chip-grid. Updates the count. |
| `data-chips-none="gridId"` | `<button>` | Unchecks all checkboxes in the `#gridId` chip-grid. Updates the count. |
| `data-scope-toggle="targetId"` | `<select>` | Shows `#targetId` when value is `"application"`, hides it otherwise. |

### Chip-grid live count

Any `change` event on `.chip-grid input[type="checkbox"]` automatically recalculates the `.chip-grid__count` text to `"N / M selected"`.

### Adding new interactions

1. Define a `data-*` attribute name.
2. Add a delegated listener in `settings.js` (always on `document`, using `e.target.closest('[data-*]')`).
3. Reference the attribute in Kotlin: `attributes["data-my-thing"] = "value"`.
4. Never use inline `onclick`, `onchange`, or any `on*` attribute — this violates CSP.

---

## htmx Patterns

KotAuth uses htmx for targeted, in-page interactions — not for navigation. The guiding principle: **use htmx for localized state transitions on the same URL, not as a page router.**

### When to use htmx

| Pattern | Example | Why it fits |
|---|---|---|
| **Inline edit/read toggle** | Profile section: read mode → edit mode → save → read mode | Same URL, same context, two states of the same content |
| **Inline form save** | Toggle a setting, save a field — swap just the notice banner ("Saved!") | High-frequency action, user stays in context |
| **Row-level actions** | Revoke a session — remove the row, update the count | Surgical DOM update, no page flash |
| **Live filtering** | User list search — swap just the table body | Filter feels instant, URL stays bookmarkable |
| **Delete with fade** | Remove a table row with `hx-swap="outerHTML swap:0.2s"` | Smooth removal without full reload |

### When NOT to use htmx

| Anti-pattern | Why it doesn't fit |
|---|---|
| **Page-to-page navigation** (e.g. General → SMTP → Security) | You're building a SPA router. Every route needs two render paths (full page + fragment). URL management, active nav state, breadcrumb, and `<title>` all need updating. Complexity cost far exceeds the marginal speed gain on low-frequency settings pages. |
| **Full form submissions that redirect** | Standard POST → redirect → GET is simple, reliable, and works with browser back/forward. Only htmx-ify if the redirect is unnecessary (user stays on the same page). |

### Implementation pattern

Kotlin side — the view function renders both the swappable target and the trigger:

```kotlin
// Trigger button
button(classes = "btn btn--ghost btn--sm") {
    attributes["hx-get"] = "/admin/workspaces/$slug/users/$id/edit-fragment"
    attributes["hx-target"] = "#profile-section"
    attributes["hx-swap"] = "outerHTML"
    +"Edit Profile"
}

// Swappable target (rendered by a shared fragment function)
div {
    id = "profile-section"
    div("ov-card") {
        div("ov-card__section-label") { +"Profile" }
        /* ... read-only content ... */
    }
}
```

Route side — detect htmx requests and return fragments:

```kotlin
get("/edit-fragment") {
    val html = renderFragment { userProfileEditFragment(workspace, user) }
    call.respondText(html, ContentType.Text.Html)
}
```

The `renderFragment()` helper in `AdminComponents.kt` renders a `DIV.() -> Unit` lambda to an HTML string without the full `<!DOCTYPE>` shell.

### Rules

1. **Always use `id` targets.** The swapped element must have a stable `id` that both the full page render and the fragment render produce identically.
2. **Fragment functions are pure.** They take data parameters and return HTML. They don't access `call`, `session`, or any HTTP context.
3. **Full page must still work.** A browser refresh on any URL must render the complete page with shell. htmx fragments are a progressive enhancement, not a replacement.
4. **Use `hx-swap="outerHTML"`** for section replacements. The fragment replaces the entire target element (including its `id`), so the next swap still has a valid target.

---

## Rules and Constraints

### Never do this

```kotlin
// ❌ Inline styles
div { style = "color: #4ade80; padding: 8px;" }

// ❌ Hardcoded color in Kotlin logic
val color = if (active) "#4ade80" else "#f87171"

// ❌ Modifier without base class
span(classes = "badge-active")
```

```css
/* ❌ Hardcoded values in CSS — use tokens */
.my-thing { color: #6b6b75; }
.my-thing { color: var(--color-muted); }  /* correct */

/* ❌ Nested selectors */
.ov-card .ov-card__row { ... }   /* wrong */
.ov-card__row { ... }            /* correct */
```

### File discipline

| Action | Where |
|---|---|
| Add a design token | `base/tokens.css` only |
| Add a tenant-theme variable | `TenantTheme.kt` field + `auth/*.css` via `var()` |
| Add a new admin component | `components/my-component.css` + import in `index-admin.css` |
| Add a new auth component | `auth/my-component.css` + import in `index-auth.css` |
| Page-specific overrides | `admin/` or `auth/` + import in entry point |
| Edit compiled output | **Never** |
| Commit compiled output | **Never** (gitignored — generated at build time) |
