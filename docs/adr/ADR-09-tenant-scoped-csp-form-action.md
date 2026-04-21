# ADR-09: Tenant-Scoped CSP `form-action`

**Status:** Accepted
**Date:** 2026-04-21

## Context

Kotauth's global `Content-Security-Policy` header included `form-action 'self'`, set by `DefaultHeaders` in `Application.kt`. The OAuth2 authorization code flow submits the login form to `POST /t/{slug}/authorize` (same origin — `'self'` allows it), but the success-path handler issues a 302 redirect back to the SPA's registered `redirect_uri` on a different origin.

Chromium enforces `form-action` against the full redirect chain (per CSP Level 3, which permits this behavior). Firefox does not. Under Chromium, the cross-origin 302 to the SPA was blocked as a `form-action` violation — users saw the login page, filled in credentials, clicked submit, and nothing happened. The browser console showed:

```
Sending form data to 'https://kotauth.<host>/t/<slug>/authorize' violates
the following Content Security Policy directive: "form-action 'self'".
```

A static allowlist of SPA origins does not scale for a multi-tenant SaaS; each tenant's SPA sits on a different origin.

## Decision

**Per-tenant `form-action` via a route-scoped Ktor plugin (`TenantCspPlugin`).** The plugin installs under `/t/{slug}`, reads the tenant slug from the path, resolves the tenant's registered redirect URI origins via `CorsPort.policyForTenant` (the same port the CORS plugin uses), and sets `Content-Security-Policy` with `form-action 'self' <origin-1> <origin-2> ...`. All other CSP directives are unchanged.

Non-tenant routes (`/admin`, `/portal`, static assets) continue to receive the strict global CSP `form-action 'self'` from `DefaultHeaders`.

## Rationale

**Architectural consistency with ADR-08.** The CORS plugin and the CSP plugin both need the same answer: "what origins has an operator authorized for this tenant's SPA traffic?" Deriving both from `client_redirect_uris` keeps one source of truth. When an admin registers a new client + redirect URI, CORS and CSP update together, in the same cache invalidation cycle.

**No new schema.** `CorsPort.policyForTenant` already returns the tenant's allowed origins. No V33 migration, no new admin UI surface, no extra operator task.

**Non-tenant routes stay strict.** Admin and portal pages are server-rendered HTML forms served same-origin. They never redirect cross-origin after a form submit, so `form-action 'self'` is correct for them and provides defense-in-depth against XSS-driven form hijacking.

**Shared `buildCspPolicy(origins)` helper.** Both the global `DefaultHeaders` config and the tenant plugin produce the policy string from the same function. Adding a directive (e.g., `connect-src`) happens in one place, not two.

## Alternatives Rejected

**Drop `form-action` entirely.** One-line patch, eliminates the symptom. Rejected because admin pages lose defense-in-depth against XSS-driven form exfiltration. The attack surface on admin is small but not zero.

**Global `form-action *`.** Same regression as above, applied universally. Loses any residual `form-action` protection for negligible simplification.

**Static env-var allowlist (analogous to the rejected CORS Option A in ADR-08).** Does not scale: each tenant's SPA origin must be added to the env var, requiring a restart. A SaaS operator onboarding a new tenant would block on platform ops.

**Dynamic CSP on the global `DefaultHeaders` (not route-scoped).** Would require `DefaultHeaders` to know about tenants, which breaks the single-responsibility boundary. The per-request lookup also runs on every response including admin, health, welcome, static — most of which don't need tenant context. Route-scoping confines the cost to the routes that actually need it.

## Consequences

- **The tenant plugin writes its CSP header via `response.headers.append`.** Ktor's `DefaultHeaders` plugin uses `appendIfAbsent` semantics, so if the tenant plugin runs first on `/t/{slug}` paths, the global CSP is suppressed for that response — exactly the intended override behavior.
- **Cache invalidation already in place.** The CORS-cache invalidation hooks (`AdminService.updateApplication`, `setApplicationEnabled`, `updateWorkspaceSettings`, `AdminApplicationRoutes` client-create) also invalidate the shared `CorsOriginCache`, so CSP updates with the same 0-latency propagation CORS enjoys.
- **Tenants with no registered clients receive `form-action 'self'` only.** Same fail-closed default as CORS — a tenant with no SPA registered has no SPA redirects to authorize. This will block OAuth flow on fresh tenants until at least one client is registered, which matches the product guarantee that a tenant must have a registered client to serve authentication at all.
- **Operator debuggability.** The browser console error names the exact `form-action` value that was enforced; an operator seeing "your SPA origin is not in `form-action`" can diagnose by registering the client or checking the redirect URI is correct.

## Implementation Notes

- `adapter/web/plugin/CspPolicy.kt` — `buildCspPolicy(origins: Set<String>): String`.
- `adapter/web/plugin/TenantCspPlugin.kt` — `createRouteScopedPlugin`, installed under `/t/{slug}` in `authRoutes`. Reads `corsPort.policyForTenant(slug)?.allowedOrigins`, emits `Content-Security-Policy`.
- `Application.kt` — global `DefaultHeaders` uses `buildCspPolicy()` (empty origin set → `form-action 'self'`).
- `AuthRoutes` — installs both `TenantCorsPlugin` and `TenantCspPlugin` inside the same `route("/t/{slug}")` block. `ApiRoutes` does not install CSP because `/api/v1/*` returns JSON, not HTML with forms.
- Tests: `CspPolicyTest` (pure string builder, 4 cases), `TenantCspPluginTest` (Ktor `testApplication`, 4 cases — known tenant with origins, known tenant with empty origins, unknown tenant, standard directives present).
