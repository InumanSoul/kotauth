# ADR-08: Multi-Tenant CORS Policy

**Status:** Accepted
**Date:** 2026-04-21

## Context

Before 1.5.7 Kotauth installed no CORS plugin. Every browser-driven request from an SPA to any OIDC endpoint (`/t/{slug}/.well-known/openid-configuration`, `/protocol/openid-connect/token`, `/userinfo`, etc.) was blocked by the browser. SPAs could not consume Kotauth at all.

Three models were evaluated:

- **A) Global env-var allowlist** — `KAUTH_CORS_ALLOWED_ORIGINS` applied to every tenant.
- **B) Separate per-tenant CORS table** — operators configure allowed origins explicitly in a dedicated admin page.
- **C) Derive per-tenant origins from registered OIDC clients** — the origin of every registered `client_redirect_uris.uri` becomes the allowed CORS origin for that tenant.

## Decision

**Option C — derive origins from `client_redirect_uris`.** One additional opt-in knob per tenant — `tenant_security_config.cors_allow_credentials` — lets operators enable `Access-Control-Allow-Credentials: true` for BFF / cookie-based cross-origin flows. Default off; PKCE public clients with Bearer tokens do not need it.

## Rationale

**One source of truth.** A separate allowlist table drifts. Every registered client already has a redirect URI; its origin is, by definition, an origin the operator has authorized for this tenant. The admin UI where operators register a client (`Applications`) is the same place they implicitly manage CORS — no separate CORS nav entry, no separate mental model.

**Scales across thousands of tenants with zero operational burden.** A new SaaS customer registers a client with their SPA's redirect URI — CORS works. No ticket to platform ops, no env var to edit per tenant.

**Preflight works without auth.** CORS preflight (`OPTIONS`) carries no Authorization, no client_id, no session — only the tenant slug from the path plus the `Origin` header. The model keys on `(tenant_slug, origin)`, which is resolvable from request data alone.

**Public endpoints remain public.** `/.well-known/openid-configuration` and `/protocol/openid-connect/certs` (JWKS) are globally readable per the OIDC spec. The plugin emits `Access-Control-Allow-Origin: *` on those paths regardless of tenant or origin, so discovery and key-fetch work for any consumer.

## Alternatives Rejected

**Option A (global env-var allowlist):** Does not scale to a multi-tenant SaaS where each tenant's SPA is hosted on a different domain. An operator adding a new tenant would have to restart Kotauth with an updated env var.

**Option B (dedicated CORS table + admin page):** Adds a parallel source of truth to `client_redirect_uris`. Operators must maintain two lists in sync. The only use case it unlocks — whitelisting an origin that is not an OIDC client's redirect URI (e.g., a backend-for-frontend or CLI tool) — can be added later via a `tenant_cors_extra_origins` table without breaking the Option C model.

**Using Ktor's built-in `install(CORS)` plugin:** Its origin allowlist is statically configured at install time. Per-request, per-tenant dynamic resolution requires a custom plugin.

## Consequences

- **Fresh-install tenants with no registered clients receive no CORS headers.** This is strict and correct — a tenant with zero clients has no browser traffic to authorize. Operators must register at least one client with a redirect URI before any cross-origin SPA can consume that tenant.
- **Policy is cached in-process with a 60-second TTL.** `CorsOriginCache` in `adapter/web/plugin/`. Every mutation on `AdminService` that changes a tenant's client list or CORS config invalidates the cache entry for that tenant, so updates propagate immediately; TTL is the safety net for any write path not yet routed through the service.
- **Denials emit a structured log line** — `WARN cors_denied tenant=X origin=Y method=Z path=P` — so an operator with log access can diagnose "my SPA is blocked" without a new admin tool.
- **Origin extraction is exact.** `https://app.example.com/callback` → origin `https://app.example.com`. Localhost dev origins (`http://localhost:3000`) work naturally via exact match. No wildcards.
- **Opt-in credentials** — `tenant_security_config.cors_allow_credentials` (V32, default `FALSE`) enables `Access-Control-Allow-Credentials: true`. Surfaced as a checkbox on the Security Policy page.

## Implementation Notes

- Domain: `domain/model/CorsDecision.kt` (sealed: `Public`, `Allowed(origin, allowCredentials)`, `Denied`), `domain/port/CorsPort.kt` (`policyForTenant(slug): CorsPolicy?`, `invalidate(slug)`), `domain/service/CorsService.kt` (`decide(slug, origin, path): CorsDecision`).
- Adapter: `adapter/persistence/PostgresCorsAdapter.kt` joins `client_redirect_uris` + `clients` + `tenants` + `tenant_security_config`, extracts origin via `java.net.URI`, filters by `clients.enabled = true`.
- Cache: `adapter/web/plugin/CorsOriginCache.kt` — `ConcurrentHashMap` with injectable `ttlMillis` and `clock` for tests.
- Ktor: `adapter/web/plugin/TenantCorsPlugin.kt` — `createRouteScopedPlugin` installed at `/t/{slug}` (auth routes) and `/t/{tenantSlug}/api/v1` (api routes). Path-aware: public paths get `ACAO: *`, client-scoped paths get exact-match allowlist. Terminates OPTIONS preflight with 204 (allowed) or 403 (denied).
- Invalidation: `AdminService.updateApplication`, `AdminService.setApplicationEnabled`, `AdminService.updateWorkspaceSettings`, and `AdminApplicationRoutes` client-create all call `CorsPort.invalidate(tenantSlug)`.
- Migration: `V32__tenant_cors_allow_credentials.sql` adds `cors_allow_credentials BOOLEAN NOT NULL DEFAULT FALSE`.
- Tests: `CorsServiceTest` (11 cases, fakes), `CorsOriginCacheTest` (4 cases, injectable clock), `TenantCorsPluginTest` (11 cases, `testApplication`).
