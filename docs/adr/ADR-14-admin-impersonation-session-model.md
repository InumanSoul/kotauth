# ADR-14: Admin impersonation session model

**Status:** Accepted (v1.10.0)
**Date:** 2026-05-01
**Supersedes:** —
**Related:** ADR-06 (sealed Result types), RFC 8693 (Token Exchange)

## Context

v1.10.0 introduced admin impersonation: an admin can act as a tenant user
without learning their password. The feature touches three load-bearing
surfaces simultaneously — the session model, the token issuer, and the audit
log — and the design choices in each were genuinely under-determined by the
existing architecture. This ADR records the decisions so future maintainers
do not have to re-derive them from the diff.

Three questions dominated the design.

1. **What does an impersonation "session" look like in the data model?** Two
   options: swap the admin's session for a new one, or carry two sessions in
   parallel.
2. **How does a downstream API distinguish "alice clicked submit" from
   "admin acting as alice clicked submit"?** Standard JWT shapes do not encode
   delegation; the access token's `sub` is just a user id.
3. **What does "stop impersonating" mean when the admin's own session has
   expired in the meantime?**

## Decision

**Parallel sessions, not a swap.** When an admin starts impersonation, a new
`sessions` row is created with `user_id = target_user_id` and a new column
`impersonator_session_id` pointing back to the admin's own session row. The
admin's session is untouched. The browser holds two cookies: `AdminSession`
(path-scoped to `/admin/*`) and `PortalSession` (path-scoped to `/`). Both
are independently valid; the admin can navigate `/admin/*` in one tab while
acting as the target user in another.

**RFC 8693 `act` claim for delegation provenance.** Access tokens and ID
tokens issued during impersonation carry a nested `act` claim:

```json
{
  "sub": "<impersonated-user-id>",
  "act": { "sub": "<admin-user-id>" },
  ...
}
```

`sub` remains the impersonated user — every existing resource server that
authorizes on `sub` continues to work without changes. The `act.sub` is the
acting admin, available to any consumer that wants to attribute or filter.
`TokenPort.issueUserTokens` gains an optional `actingSubject: UserId?` and
`JwtTokenAdapter` stamps the claim only when it is non-null; the normal
token shape is preserved for every non-impersonated flow.

**Cascade revocation at the repository layer.** When the admin's own session
row is revoked — through logout, `revokeAllForUser`, or any other path —
`SessionRepository.revoke()` and `revokeAllForUser()` also revoke every
active impersonation child whose `impersonator_session_id` matches. The
cascade lives in the repository, not at every call site, because the
invariant "no impersonation session outlives its admin session" must hold
regardless of which path triggered the parent revocation.

## Alternatives considered

**Session swap (rejected).** Replace the admin's session cookie with a new
one for the target user; restore the admin session on "stop." This is what
some IAM products do. We rejected it for two reasons. First, it creates an
orphan-state problem: if the admin's session expires mid-impersonation, the
restore-path has nothing to restore to, and the admin gets bounced to login
with no obvious recovery. Second, it is hostile to the multi-tab workflow —
an admin investigating a user's experience often wants to keep the admin
console open alongside the impersonated view.

**`amr` claim instead of `act` (rejected).** OIDC Core defines
`amr` (Authentication Methods References) and an admin could conceivably add
`"impersonation"` to that array. `amr` describes *how* the user
authenticated, not *who* is acting on their behalf. RFC 8693's `act` claim
exists precisely for this case; using it preserves SCIM-style delegation
semantics that downstream tools may already understand.

**Custom flat claim like `acting_admin_id` (rejected).** A flat claim is
simpler to consume but is not standard, encodes only the id (no room for
nested attributes like `act.iss` if we ever federate impersonation), and
would have to be invented per-tenant. RFC 8693 already standardised the
shape — there is no upside to diverging.

**No cascade — let impersonation sessions expire naturally (rejected).**
Impersonation tokens have the same lifetime as normal sessions. Without
cascade, an admin logging out leaves valid impersonation tokens in the wild
for up to the token lifetime. That is a dangling-privilege gap and the
opposite of least-privilege.

## Consequences

**Positive:**

- The admin shell survives an impersonation session ending — no
  re-authentication required.
- Downstream APIs that verify Kotauth tokens see standard OIDC tokens with a
  nested `act` claim; consumers can opt in to delegation-aware logic without
  any breaking change to the existing claim shape.
- Cascade revocation is enforced at one layer (the repo), so adding a new
  admin-logout path in the future cannot accidentally leave impersonation
  tokens valid.
- Audit attribution is complete: every action performed during impersonation
  carries `act.sub` on its token, and the `ADMIN_IMPERSONATION_STARTED` event
  records `admin_user_id`, `admin_username`, `target_user_id`,
  `target_username`, `admin_session_id`, and `impersonation_session_id`.

**Negative:**

- Two cookies coexist in the same browser, which is a slightly larger surface
  area than a single-cookie design. Path-scoping prevents cross-contamination
  but new admin routes must be careful to live under `/admin/*` so they
  receive the `AdminSession` cookie rather than the `PortalSession`.
- The portal UI must explicitly guard destructive self-service actions
  ("Delete account", "Change password") to refuse during impersonation. The
  banner is the user-visible cue; the route handlers also return 403. Belt
  and suspenders, because the consequence of forgetting one is a real-data
  mutation by the wrong actor.
- `client_default_roles` (v1.11.0) and any other future feature that fires
  on registration cannot fire on impersonation — the impersonated user is
  not "registering," they are being acted on. So far this has not required
  a special case, but new "first-time setup" flows need to consider it.

## References

- RFC 8693 §4.1 — `act` claim shape
- V41 migration: `impersonator_session_id` on `sessions`
- `ImpersonationService`, `JwtTokenAdapter`, `SessionRepository`
