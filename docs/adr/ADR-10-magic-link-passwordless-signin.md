# ADR-10: Magic-link passwordless sign-in

**Status:** Accepted (v1.7.0)
**Date:** 2026-04-27
**Supersedes:** —
**Related:** ADR-04 (admin-service mutations), ADR-05 (client-secret hashing)

## Context

SaaS integrators want a passwordless sign-in option for end users. The two
common shapes are WebAuthn/passkeys (high assurance, hardware bound) and
emailed one-time links (lower assurance, zero enrollment friction). For
v1.7.0 we ship the emailed-link variant: it's the cheapest way to remove
the password from the happy path for users who already trust their email
account, and it composes cleanly with our existing password-reset/invite
token infrastructure.

The feature is per-tenant opt-in (`tenant_security_config.magic_link_enabled`,
default FALSE) so existing tenants are unaffected.

## Decision

### Token lifecycle

- 15-minute TTL. Long enough to switch to a phone or open a webmail tab,
  short enough that an exfiltrated link is unlikely to be useful.
- Single-use. `consumed_at` is stamped on the first successful consume;
  subsequent attempts return the same generic failure as an unknown
  token.
- Stored as SHA-256 hash in `password_reset_tokens` with
  `purpose = 'MAGIC_LINK'`. Cross-purpose use is rejected at the service
  layer — feeding a `PASSWORD_RESET` token to `consumeMagicLink` returns
  `TokenInvalid` without consuming it.
- Issuing a new magic link for a user supersedes any prior unconsumed
  magic-link token for the same user (defense against link stockpiling).

### User-enumeration protection

`initiateMagicLink` always returns `Success` regardless of branch:

- Unknown tenant slug → silent success
- Tenant feature disabled → silent success
- SMTP not configured → silent success
- Unknown email → silent success
- Disabled user → silent success

The route always redirects to `?sent=true` and renders the same
"check your inbox" page. Rate-limit hits also return the same response —
no timing oracle, no status code variance.

### MFA invariant

Magic links verify email possession, **not** the second factor. After
`consumeMagicLink` succeeds, the flow re-enters the standard authorization
code path via `completeAuthorizationCodeFlow`, which routes through MFA
when the user has it enrolled. Magic link is **not** a backdoor around
MFA.

### Required-action interaction

- `SET_PASSWORD`: cleared on successful consume. A user who was invited
  but never set a password can use magic links indefinitely if the tenant
  enables it.
- `CHANGE_PASSWORD`: blocks the consume. A user with a forced password
  change still has to complete that change through the normal force-change
  flow before they can sign in by any means.

### Same-device cookie binding

Consumption requires the `KOTAUTH_AUTH_CONTEXT` cookie set during the
original `/authorize` request. This cookie carries the OAuth client_id,
redirect_uri, scope, state, and PKCE challenge so consumption can resume
the authorization-code flow.

The `POST /magic-link/send` handler refreshes this cookie's timestamp so
the user gets a full 5-minute window from the moment they request the
link, not from when they originally hit `/authorize`.

If the cookie is absent at consume time (different browser, expired
session, private window), the user sees a friendly error directing them
back to the login page in the same browser to request a new link. We
**deliberately do not** auto-create a session-only token without the
OAuth context — doing so would leave the user authenticated but unable to
return to the original SaaS app, which is worse UX than asking them to
restart.

### Reuse of `password_reset_tokens` table

The schema is identical (hash, purpose, expiry, consumed_at, ip, user
fk). A separate `magic_link_tokens` table would mean duplicating five
columns and five indexes for a 4-line `purpose` differentiator. The
service-layer purpose guard is sufficient for safety; the indexes on
`(token_hash)` and `(user_id, purpose)` cover both paths.

### Rate limiting

Per-IP + per-tenant-slug bucket on `POST /magic-link/send`. We
intentionally do **not** key by email — that would create an
enumeration oracle (different rate-limit behavior for known vs. unknown
emails). Accept that an attacker on a single IP gets one bucket across
many emails: the silent-success behavior of `initiateMagicLink` means
they learn nothing per attempt anyway.

### What `consumeMagicLink` does to the user record

- Marks `email_verified = true` (the user proved control of the address)
- Clears `SET_PASSWORD` from `required_actions` if present
- Records `MAGIC_LINK_CONSUMED` audit event
- Does **not** touch `password_hash`, sessions, MFA, or roles

## Consequences

### Positive

- Zero new dependencies, zero new tables. Reuse of existing infra means
  the diff is small and reviewable.
- Composes cleanly with invites: an invited user can consume the invite
  to set a password, *or* request a magic link instead and skip the
  password entirely (if the tenant has both invites and magic links
  enabled).
- Email-verification side effect: any successful magic-link consume
  proves email control, so we mark `email_verified = true` for free.

### Negative — known limitations

1. **Cross-device consumption is not supported in v1.7.0.** A user who
   requests a link on their laptop and clicks it on their phone will see
   the friendly "open this link in the same browser" error. v1.7.1 plans
   to lift this by creating a `PortalSession` directly on consume when no
   OAuth context is present, redirecting the user to their tenant's
   account dashboard rather than the requesting app. That requires deeper
   thought on which app to land them in (no single canonical answer when
   the OAuth context is gone), so it's deferred.
2. **5-minute OAuth-context cookie window** still bounds the same-device
   flow. If the user takes longer than 5 minutes between hitting
   `/authorize` and requesting their first link, the cookie is gone.
   `POST /magic-link/send` refreshes it, so the relevant window is
   "request to click," not "authorize to click."
3. **No standalone magic-link entry point.** Today, magic links only
   bootstrap from inside an `/authorize` request. There is no `/login`
   page that lets a user request a link without an OAuth client. Adding
   one means picking a destination, which falls under the same v1.7.1
   scope as cross-device consume.
4. **Email is the second-strongest factor we have, not the strongest.**
   Compromised inbox = compromised account when magic links are enabled.
   Tenants in regulated environments should leave the toggle off and
   rely on password + MFA. The toggle copy on the Security Policy page
   makes this trade-off explicit.

### Neutral

- Reusing `password_reset_tokens` for a fourth purpose increases the
  blast radius of any future schema change to that table. We accept this
  in exchange for not multiplying token tables.

## Alternatives considered

- **WebAuthn / passkeys.** Higher assurance, but requires significant
  new domain (credential storage, attestation, browser API integration)
  and zero of our existing tenants have asked for it. Deferred.
- **Separate `magic_link_tokens` table.** Cleaner blast radius but a
  duplication of columns and indexes for no functional gain, given the
  service-layer purpose guard.
- **Auto-create session on cross-device consume.** Authenticates the
  user but leaves them stranded relative to the requesting OAuth app.
  Rejected as worse UX than the friendly error.
- **Rate limit by email.** Creates an enumeration oracle. Rejected.
