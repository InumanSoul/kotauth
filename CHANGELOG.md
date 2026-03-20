# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

- **BEM design system — settings pages**: Rewrote Security Policy, SMTP, Identity Providers, API Keys, and Webhooks pages from legacy CSS to BEM components (`ov-card`, `edit-row`, `toggle-row`, `chip-grid`, `copy-field`, `key-table`)
- **BEM design system — RBAC pages**: Rewrote Roles (list/create/detail) and Groups (list/create/detail) to BEM with `data-table`, `page-header`, `edit-row`, `key-table`, and `empty-state` components
- **`settings.js`** — CSP-safe JavaScript for all admin interactions: clipboard copy (`data-copy`), confirm dialogs (`data-confirm`), chip-grid toggles (`data-chips-all`/`data-chips-none`), scope field toggle (`data-scope-toggle`)
- **`copy.svg`** icon for clipboard copy buttons
- **`btn--icon`** CSS modifier for icon-only square buttons (24×24)
- **`ov-card__section-label--danger`** CSS modifier for danger zone headings
- **`badge--danger`** CSS modifier (red variant) for failed delivery status
- **`copy-field`** CSS component for monospace value + copy button (callback URLs, one-time secrets)
- **Frontend Architecture docs** — added JavaScript (`settings.js`) and htmx patterns sections to `docs/FRONTEND_CSS.md`

### Changed

- **`copyBtn()` rewritten** — replaced inline `onclick` with `data-copy` attribute and SVG icon; now uses `btn btn--ghost btn--icon` base classes
- **Lock icon** — replaced 🔒 emoji with inline SVG (`lock.svg`) on user detail page
- **Copy buttons across all pages** — replaced `⎘` unicode glyph with `copy.svg` icon in Identity Providers, API Keys, and Webhooks copy-field buttons
- **User detail page** — converted Profile, Active Sessions, and Danger Zone from legacy `section`/`section__title` to `ov-card`/`ov-card__section-label`
- **Branding page** — replaced `form-section__label` with `ov-card__section-label`; each section now wrapped in its own `ov-card`
- **ov-card stacking** — increased sibling gap from 12px to 20px
- **`ov-card__section-label`** — now flex row (`justify-content: space-between`) to support action buttons alongside the title
- **`empty-state__icon`** — added `color: var(--color-muted)` and `svg { width: 16px; height: 16px }` for consistent icon rendering
- **Reset password button** — now always visible on user detail page; disabled with tooltip when SMTP is not configured
- **`docs/FRONTEND_CSS.md`** — updated folder structure, file responsibilities table, and component reference to reflect all new BEM components and CSS files

### Fixed

- **Global label bleed** — added explicit `text-transform: none` resets on `.check-row`, `.radio-row`, `.toggle`, and `.scope-chip` to counteract global `label { text-transform: uppercase }` from `form.css`
- **Checkbox/radio sizing** — added `padding: 0; min-width: 14px; min-height: 14px` resets to prevent global `input` padding from inflating checkbox and radio inputs
- **Branding double-spacing** — removed flex gap from `.branding-form` to prevent stacking with ov-card sibling margin
- **Branding double-border** — removed `border` from `.preset-group` and `.color-grid` now that they sit inside bordered ov-cards

---

## [1.0.2] - 2026-03-19

### Added

- `docker/docker-compose.prod.yml` — production overlay that adds a Caddy sidecar for automatic Let's Encrypt TLS
- `docker/Caddyfile` — Caddy reverse proxy configuration used by the production compose stack
- `docker/docker-compose.dev.yml` — dedicated compose file for contributors building from source (`make up`)
- `DB_HOST` environment variable — hostname component for constructing the JDBC URL (default: `db`)
- `DB_PORT` environment variable — port component for constructing the JDBC URL (default: `5432`)
- `.env.example` committed to the repository as the canonical environment template
- `docs/guides/production-deployment.md` — full production deployment walkthrough covering Caddy, nginx, Traefik, upgrades, and backup

### Changed

- Docker files reorganised into a `docker/` subdirectory (`docker-compose.yml`, `docker-compose.dev.yml`, `docker-compose.prod.yml`, `Caddyfile`)
- `docker/docker-compose.yml` now uses the pre-built GHCR image (`ghcr.io/inumansoul/kotauth:latest`) instead of building from source — no repo clone required to run Kotauth
- `DB_URL` is now an optional override. When not set, the compose stack constructs it from `DB_HOST`, `DB_PORT`, and `DB_NAME`. Setting `DB_URL` directly takes full precedence, enabling external or managed database connections (RDS, Supabase, Neon, etc.) with custom JDBC parameters such as `?sslmode=require`
- `README.md` rewritten: zero-clone quickstart as the primary path, CI/Docker/release/license badges, available image tags table
- Makefile `make up` and related targets updated to reference `docker/docker-compose.dev.yml`

### Fixed

- `DB_URL` in the compose stack was hardcoded and would silently override any `DB_URL` value set in `.env`
- GitHub repository URL placeholder (`your-org/kotauth`) corrected throughout docs and config

---

## [1.0.1] - 2026-03-19

### Added

- Admin console welcome page with live health details in development mode
- New helper utilities for internal route handling

---

## [1.0.0] - 2026-03-17

Initial stable release.

### Added

- **OAuth2 / OIDC provider** — Authorization Code + PKCE, Client Credentials, Refresh Token rotation, token introspection (RFC 7662), token revocation (RFC 7009), OIDC discovery (RFC 8414), JWKS endpoint
- **Multi-tenancy** — fully isolated workspaces with per-workspace RS256 key pairs, user directories, OAuth clients, token policies, and branding; master workspace for platform administration
- **RBAC** — tenant-scoped and client-scoped roles, composite role inheritance with BFS expansion and cycle detection, group hierarchy with nested membership
- **MFA** — TOTP (RFC 6238) with QR code enrollment, 10 recovery codes per user, per-workspace policy (`optional`, `required`, `required_for_admins`)
- **Social login** — Google and GitHub OAuth2 with automatic account linking by email address
- **User self-service portal** — email verification, password reset (no email enumeration), profile editing, password change, session listing and revocation, MFA enrollment
- **Admin console** — workspace settings, user management, application management, API key management, audit log viewer, webhook management
- **Webhooks** — HMAC-SHA256 signed delivery for 8 event types with exponential backoff retry (immediate, 5 min, 30 min)
- **REST API v1** — 30+ endpoints with API key authentication; OpenAPI 3.1 spec and Swagger UI at `/t/{slug}/api/v1/docs`
- **Audit logging** — 30+ immutable event types, append-only, queryable via API and admin console
- **Security baseline** — bcrypt passwords, AES-256-GCM SMTP credential encryption, SHA-256 refresh token and API key hashing, IP-based rate limiting, security response headers, CSRF protection, startup validation for production mode
- **Flyway migrations** — versioned schema management, runs automatically on startup
- **Multi-stage Docker build** — ~120 MB runtime image (`eclipse-temurin:17-jre`), published to `ghcr.io/inumansoul/kotauth`
- **CI/CD** — GitHub Actions pipelines for lint, test, and Docker image publishing on version tags
- **Integration guide** — React SPA with TanStack Router and `oidc-client-ts`

---

[Unreleased]: https://github.com/inumansoul/kotauth/compare/v1.0.2...HEAD
[1.0.2]: https://github.com/inumansoul/kotauth/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/inumansoul/kotauth/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/inumansoul/kotauth/releases/tag/v1.0.0
