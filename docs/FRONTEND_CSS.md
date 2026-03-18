# Frontend CSS Architecture

CSS source layer for KotAuth's UI. Component-scoped source files compiled to
two minified bundles at build time using LightningCSS. No Node.js in the final
Docker image.

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
14. [Rules and Constraints](#rules-and-constraints)

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
│       │   ├── button.css             ← .btn, .btn-ghost, .btn-danger, .btn-sm, .btn-full
│       │   ├── badge.css              ← .badge, .badge-active, .badge-error, etc.
│       │   ├── alert.css              ← .alert, .alert-error, .alert-success, .alert-warn
│       │   ├── card.css               ← .card, .card-body, .card-title, .card-subtitle
│       │   ├── form.css               ← input, select, textarea, label, .field
│       │   ├── stat-card.css          ← .stat-grid, .stat-card, .stat-label, .stat-value
│       │   ├── table.css              ← table, th, td, .td-muted, .td-code
│       │   ├── empty-state.css        ← .empty-state, .empty-state-icon, .empty-state-text
│       │   └── ov-card.css            ← .ov-card, .ov-card__row, .ov-card__value (BEM)
│       │
│       ├── admin/                     ← Admin-specific pages
│       │   ├── login.css              ← .login-shell, .brand-mark, .login-card
│       │   └── form-card.css          ← .form-card, .form-section-title, .checkbox-row
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
| `layout/content.css` | Admin | Main content area, page header, breadcrumb |
| `components/button.css` | Admin | All button variants |
| `components/badge.css` | Admin | Status and type badges |
| `components/alert.css` | Admin | Inline feedback banners |
| `components/card.css` | Admin | Generic card containers |
| `components/form.css` | Admin | Input, select, textarea, label, .field |
| `components/stat-card.css` | Admin | Metric stat grid |
| `components/table.css` | Admin | Data tables |
| `components/empty-state.css` | Admin | Empty content placeholders |
| `components/ov-card.css` | Admin | BEM overview / key-value card |
| `admin/login.css` | Admin | Admin login page layout |
| `admin/form-card.css` | Admin | Settings and edit form cards |
| `auth/shell.css` | Auth | Body centered layout |
| `auth/brand.css` | Auth | Tenant brand header |
| `auth/card.css` | Auth | Auth card with shadow |
| `auth/form.css` | Auth | Auth form inputs (larger sizing) |
| `auth/button.css` | Auth | Full-width primary button |
| `auth/alert.css` | Auth | Auth feedback banners (hardcoded functional colors) |
| `auth/social.css` | Auth | Social login divider and provider buttons |
| `auth/misc.css` | Auth | Footer links and dividers |

---

## Component Reference

### `ov-card` — reference BEM component

```css
.ov-card             { background: var(--color-card); border: var(--border-md); ... }
.ov-card__row        { display: grid; grid-template-columns: 152px 1fr; ... }
.ov-card__label      { font-size: 12px; color: var(--color-muted); }
.ov-card__value      { font-size: 13px; color: var(--color-text); }
.ov-card__value--mono   { font-family: var(--font-mono); color: var(--color-accent); }
.ov-card__value--muted  { color: var(--color-muted); font-style: italic; }
.ov-card__value--empty  { color: var(--color-subtle); font-style: italic; }
.ov-card__row--stacked  { display: block; ... }
.ov-card__actions    { padding: ...; border-top: var(--border); ... }
```

Kotlin usage:

```kotlin
div(classes = "ov-card") {
    div(classes = "ov-card__row") {
        span(classes = "ov-card__label") { +"Client ID" }
        span(classes = "ov-card__value") {
            span(classes = "ov-card__value--mono") { +app.clientId }
        }
    }
    div(classes = "ov-card__row") {
        span(classes = "ov-card__label") { +"Status" }
        span(classes = "ov-card__value") {
            span(classes = "badge ${if (app.active) "badge-active" else "badge-disabled"}") {
                span(classes = "badge__dot") {}
                +if (app.active) "Active" else "Disabled"
            }
        }
    }
}
```

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
// Correct
span(classes = "badge ${if (app.active) "badge-active" else "badge-disabled"}") {
    span(classes = "badge__dot") {}
    +if (app.active) "Active" else "Disabled"
}

// Wrong — hardcoded color in Kotlin
div { style = "color: #4ade80; padding: 8px;" }
val color = if (active) "#4ade80" else "#f87171"
```

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
