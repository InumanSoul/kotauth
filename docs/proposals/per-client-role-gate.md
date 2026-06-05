# Proposal: per-client allowed-roles gate

**Status:** Proposed — open for review
**Author:** Zion Platform Engineering
**Date:** 2026-06-05

## Context

Kotauth's authorize endpoint validates that a (client_id, redirect_uri,
state, PKCE) request is well-formed and that the user's credentials check
out. It does **not** enforce that the authenticated user is *allowed at
this client*. Any active user in the tenant can complete the Authorization
Code flow against any client in the tenant.

This is the standard "single realm, multiple clients" pattern shared by
Keycloak, Auth0 and most OIDC providers. It is not a defect — but it is a
gap for deployments that share a user pool across audiences with strong
isolation requirements.

### Zion's case

Zion runs a brokerage stack with three audiences:

- **Backoffice operators** — staff users authenticating against the admin
  SPA. Carry roles like `backoffice:read`, `ledger:audit:read`,
  `oms:write`.
- **Brokerage customers** — retail users authenticating against the
  portfolio SPA. Carry `brokerage:customer`.
- **KYC applicants** — users still in onboarding. Carry
  `onboarding:applicant`.

A customer must not learn that the backoffice SPA exists. Today Kotauth
will happily mint a customer-credential token against the
`zion-admin-spa-client` because the (customer user → admin client) pair
is in the same tenant. The application layer (admin-BFF) is the only
thing that can reject.

### Zion's stopgap (shipped 2026-06-05)

We solved this by **splitting into two Kotauth tenants per deployment**:
`zion-staff` (admins/operators) and `zion-public` (customers/applicants).
The boundary now lives at the OIDC layer — distinct `iss`, distinct JWKS,
distinct user pools, distinct discovery URLs. Customer users physically
do not exist in the staff tenant, so the authorize endpoint rejects
cross-tenant login attempts at the client lookup step.

This works. It uses Kotauth as designed. But it introduces an awkward
operational reality: a real human who is both a brokerage employee and
a brokerage customer (very common — employees often have personal
trading accounts) has **two Kotauth identities** with two distinct `sub`
values. Linking the two for HR offboarding, MFA recovery, or audit
correlation becomes an application-layer concern.

A single-tenant model with a per-client role gate would let us keep one
`sub` per human and still enforce strong audience separation at the
OIDC layer. That's what this proposal is about.

## Proposed feature

Add a `client_allowed_roles` (or similar) join between `clients` and
`roles`. When set, the authorize endpoint enforces that
`user.roles ∩ client.allowed_roles ≠ ∅` *after* credential validation
and *before* code issuance. When unset, the client behaves exactly as
today (any tenant user is welcome).

### Schema sketch

```sql
CREATE TABLE client_allowed_roles (
    client_id INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    role_id   INTEGER NOT NULL REFERENCES roles(id)   ON DELETE CASCADE,
    PRIMARY KEY (client_id, role_id)
);
```

### Authorize-endpoint logic

In `OAuthService.issueAuthorizationCode()` (or the equivalent for the
hosted-OTP flow), after `applicationRepository.findByClientId(...)` and
credential validation:

```kotlin
val allowedRoleIds = clientAllowedRoleRepository.findFor(client.id)
if (allowedRoleIds.isNotEmpty()) {
    val userRoleIds = userRoleRepository.findRoleIdsFor(user.id)
    if ((allowedRoleIds intersect userRoleIds).isEmpty()) {
        return OAuthError.AccessDenied // or a more specific code
    }
}
```

### UX

A user whose roles do not intersect the client's allowed roles should
see the same screen any other access-denied case shows — a generic "this
account is not authorized for this application" page. **Do not** include
helpful redirects like "you might be looking for portal.x.com.py" — the
whole point of the gate is to prevent the user from learning about other
applications they shouldn't see.

Log the rejection server-side at WARN level for security monitoring,
including the (user_id, client_id, tenant_id) tuple.

### Admin UI

An admin should be able to:

1. View the list of allowed roles for a given client.
2. Add/remove a role from the allowed-roles set.
3. See an empty allowed-roles set as the legacy "any tenant user" behaviour.

This is a straightforward extension of the existing client admin panel.

## Alternatives considered

### Per-client `default_roles` already exists

Kotauth has `client_default_roles` — roles automatically granted to a
user at self-registration via that client. That's a *granting* mechanism,
not a *gating* one. It doesn't restrict who can complete the flow.

### Application-layer gate in every BFF

A BFF can validate required scopes on `/auth/callback` before persisting
the session. This works but pushes the boundary into every consumer's
code; a missed BFF = a leak.

### Separate realms per audience

What Zion shipped. Works, but spreads user identity across multiple
records for any human who belongs to multiple audiences.

## Effort estimate

- Schema migration: ~10 LOC + one Flyway file.
- Repository + domain port: ~50 LOC.
- Authorize-endpoint integration: ~30 LOC + tests.
- Admin UI panel: ~150 LOC (HTML + form handler).
- Error template + i18n: ~20 LOC.
- Tests (domain + route integration): ~200 LOC.

Roughly half a sprint for a senior engineer familiar with Kotauth's
hexagonal layout.

## Backwards compatibility

Clients with no `client_allowed_roles` entries behave exactly as today.
Deployments that don't opt in see no change. The migration is additive.

## Cross-references

- Zion ADR-23 — Two-tenant Kotauth identity split:
  `~/Developer/zion-ecosystem/docs/adr/ADR-23-two-tenant-kotauth-identity-split.md`
  (documents the realm-split workaround and the rationale for shipping it
  before this feature exists).
