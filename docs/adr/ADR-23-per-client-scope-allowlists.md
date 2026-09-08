# ADR-23 — A client's scopes are granted per client, not inherited from the API

**Status:** Accepted — v1.25.0
**Context:** RFC 6749 §3.3, RFC 8707, RFC 9068 §5

## Context

Authorizing a client against a resource server granted it **every scope that resource server
declared**. `OAuthService.narrowScopes` intersected the request against
`resolvedResources.flatMap { it.scopes }` — what the API offers — with nothing client-specific in
the intersection. No per-client scope allowlist existed anywhere in the model, and the codebase
knew it: `EmailOtpService` carried `TODO(v1.13): derive from client.allowedScopes once that field
exists`, open since v1.13.

This was measured on a live integration, not inferred. A client created and authorized with the
sole intent of ingesting documents:

```
client_id: help-center-ingesta      (created for: assistant:ingest, nothing else)

token request, scope="assistant:ingest assistant:keys assistant:handoff"
  -> granted, all three

GET /admin/stats with that token
  -> HTTP 200   (read the question log of every tenant of that service)
```

The consuming service was doing everything right — one scope per capability, enforced per route.
That separation is real for a **token** and was meaningless for a **client**: any authorized
client could mint whatever token it wanted.

Separate credentials still bought independent revocation and a real `sub` in the audit trail.
They did not buy least privilege, which is what an operator reading the admin console would
reasonably assume they bought.

## Decision

A client's scopes on a resource server are an explicit grant, stored per (client, resource
server) pair.

### Storage

`client_authorized_scopes(client_id, resource_server_id, scope)` — V67. A side table, matching
V51's `client_authorized_resources` and V59's `client_grant_types`, rather than a column on
`clients` or a JSONB blob. The composite foreign key targets `client_authorized_resources`, so a
scope grant cannot outlive the authorization it qualifies and un-authorizing an API removes its
scope rows by cascade.

### Enforcement

`narrowScopes` becomes a three-way intersection: **requested ∩ declared-by-resource ∩
granted-to-client**. All three grant types funnel through that one function, so authorization
code, refresh and client credentials are covered by a single change.

`EmailOtpService` assigns scopes on its back-channel path outside `narrowScopes` and applies the
same lookup separately.

**`clientAllowedScopes` is a required parameter, not a defaulted one.** A default would let a new
call site silently reproduce the original bug. An absent or empty entry means the client may
request **nothing** on that resource — never everything. Treating absence as unrestricted is
exactly the behaviour being removed.

On the refresh path the client may be null, because a soft-deleted application no longer resolves.
That case now yields no grants and therefore no scopes, rather than falling back to the resource
server's full declared set.

### Migration default

V67 backfills one row per scope each resource server currently declares, for every existing
authorization. That reproduces today's behaviour exactly: no client gains a scope it could not
already request, and none loses one. Narrowing begins only when an operator edits a client's
scopes or authorizes a new API.

A newly authorized API arrives with all of its declared scopes checked — the same default, applied
to new pairs. Re-saving an unchanged authorization preserves whatever the operator had chosen.

A scope the resource server does not declare is refused rather than stored, so the allowlist
cannot drift from the API it constrains. A grant row for a scope the API later stops declaring
becomes inert, because the intersection still requires the resource server to declare it.

## Consequences

Operators get least privilege between clients. The admin console's authorized-APIs page now shows
each API's scopes as checkboxes under it, so the grant is visible where the authorization is made
— previously nothing in the UI indicated that authorizing an API granted all of its scopes.

Adding a new scope to an API does **not** grant it to existing clients. That is deliberate: silent
propagation is how the original problem would return. The operator grants it explicitly.

This unblocks the OAuth consent screen, which could not honestly be built on the previous model —
it would have told a user "this application wants access to X" as a per-client claim the platform
could not enforce.

## Alternatives considered

**A column on `clients`.** Simpler to read, but the grant is per (client, resource server), so a
single column would either denormalise or lose the pairing.

**Treat an empty grant set as unrestricted.** Convenient for migration, and precisely the
ambiguity that produced the original bug. A `NOT NULL DEFAULT` mistake would reintroduce it
silently.

**Enforce at the resource server instead.** Moves the problem to every consuming service and makes
the guarantee unverifiable from Kotauth. The authorization server issues the token; it is the
place that can constrain it.
