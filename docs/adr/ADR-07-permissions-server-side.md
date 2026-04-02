# ADR-07: Fine-Grained Permissions Are Server-Side Only

**Status:** Accepted
**Date:** 2026-04-02

## Context

Kotauth is adding fine-grained permissions (`resource:action` strings like `orders:read`, `patient_records:write`) assigned to roles via a `role_permissions` M2M table. The key architectural decision is where downstream services resolve a user's effective permissions.

Three options were evaluated:
- **A) Always in JWT** — permissions embedded in every access token
- **B) Server-side only** — permissions resolved via API/introspection
- **C) Hybrid opt-in** — per-application toggle (Auth0's model)

## Decision

**Option B — Server-side only.** Permissions are never embedded in JWTs.

- **Roles and groups remain in the JWT** via `realm_access.roles` and `resource_access.{clientId}.roles` claims (identity context, unchanged)
- **Permissions are resolved via introspection** (`POST /introspect` returns `permissions: [...]` alongside token metadata) and a dedicated API endpoint (`GET /t/{slug}/api/v1/users/{id}/permissions`)
- The `AccessTokenClaims`, `TokenPort`, and `JwtTokenAdapter` are not modified

This follows Clerk's model: the IdP owns identity (who you are, what roles you hold), authorization decisions (what you're allowed to do) are resolved server-side per request.

## Rationale

**The IdP should care about identity, not authorization policy.** Roles are identity context — they describe who a user is within an organization. Permissions are authorization decisions — they describe what actions are allowed. Mixing both into the token conflates two concerns with different change frequencies and propagation requirements.

**Immediate propagation.** A permission revoked in the database is reflected on the next API call. No stale-state window bounded by token TTL. This matters for security-sensitive permissions (`patient_records:write`, `billing:admin`) where a 15-60 minute delay is unacceptable.

**No token bloat.** At 50+ permissions, JWT size approaches nginx's default 8KB header limit. Keycloak has no guard against this and users discover it via cryptic 431 errors. Server-side resolution eliminates this class of problem entirely.

**Simpler implementation.** No changes to the token issuance pipeline (`OAuthService`, `TokenPort`, `JwtTokenAdapter`, `FakeTokenPort`). The permission system is additive — a new `PermissionRepository` port, introspection extension, and API route. Zero risk of breaking existing token consumers.

**Industry direction.** The market is moving toward thin tokens + server-side authorization checks (WorkOS FGA, OpenFGA, Oso, Permit.io). Even Auth0's opt-in toggle exists primarily for backward compatibility. Clerk's approach (roles in token, permissions server-side) is the modern consensus.

## Alternatives Rejected

**Option A (always in JWT):** Token bloat at scale, stale permissions until refresh, no recall mechanism for revoked permissions. Keycloak's approach — proven to cause operational issues.

**Option C (hybrid opt-in per application):** Auth0's model. More flexible but adds implementation complexity (both paths), requires admin operators to understand propagation trade-offs, and the opt-in flag becomes a footgun for security-sensitive permissions. The flexibility is not worth the complexity for Kotauth's target market.

## Consequences

- Downstream services that need permission checks must call Kotauth's introspection endpoint or the permissions API. This adds a network dependency.
- Services should cache permission results with a short TTL (30-60s recommended) to balance freshness against latency.
- Offline JWT verification still works for identity and role checks — only permission checks require a Kotauth call.
- The `PermissionRepository.resolveEffectivePermissions` query runs as a separate SELECT after role resolution, not as an extension of the existing `resolveEffectiveRoles` CTE. This keeps both queries focused and independently optimizable.

## Implementation Notes

- New port: `PermissionRepository` with `resolveEffectivePermissions(userId, tenantId): List<String>`
- Query: `SELECT p.name FROM permissions p JOIN role_permissions rp ON rp.permission_id = p.id WHERE rp.role_id IN (:resolvedRoleIds) AND p.tenant_id = :tenantId`
- Extend `IntrospectionResult.Active` to include `permissions: List<String>`
- New API route: `GET /t/{slug}/api/v1/users/{id}/permissions` (requires `permissions:read` scope)
- Admin UI: permission CRUD pages, role detail shows assigned permissions
- Migration: `permissions` table + `role_permissions` M2M table
