# ADR-13: OIDC silent SSO via path-scoped witness cookie

**Status:** Accepted (v1.8.1)
**Date:** 2026-04-29
**Supersedes:** —
**Related:** ADR-01 (hexagonal architecture), `docs/guides/react-spa-tanstack-router.md`

## Context

Through v1.8.0, every visit to `/t/{slug}/authorize` rendered the login
form even when the user had successfully authenticated minutes earlier
in the same browser. A user who logged into the SaaS SPA via Kotauth and
then clicked "Manage Account" landed on the portal login screen instead
of being silently authenticated — generating "why am I logged out?"
support tickets and badly under-performing the bar set by Auth0,
Keycloak, and Clerk.

Three things made the original sketch insufficient and forced this ADR:

1. **The first proposal — "append `&prompt=none` to the portal authUrl"
   — was a one-line change that doesn't actually do anything.** Kotauth's
   `/authorize` GET handler had no awareness of `prompt`; even if it had,
   `completeAuthorizationCodeFlow` did not set any auth-server-domain
   cookie on successful login. After OAuth completes, the browser holds
   zero Kotauth-domain cookies — the auth server has no SSO state to
   consult on the next `/authorize` GET. Silent auth is not a flag, it is
   a witness-cookie protocol.
2. **The OIDC spec is precise about what "silent auth" means.** Core
   §3.1.2.1 defines `prompt`, `max_age`, `id_token_hint`; §3.1.2.6
   defines the `login_required` failure mode; §2 / §3.1.3.7 require the
   `auth_time` claim on the ID token. We are an OIDC server with full
   discovery, JWKS, PKCE — silent SSO has to land at that quality bar,
   not "good enough for our portal."
3. **Tenant-scoping is not optional.** Kotauth runs many tenants on the
   same hostname (`https://auth.example.com/t/acme`,
   `/t/widgets`, …). A domain-scoped SSO cookie would let one tenant
   silent-auth users into another. This shapes the cookie design more
   than any other constraint.

## Decision

### Stateful, signed, opaque, path-scoped — `KOTAUTH_SSO`

We mint a server-signed cookie at every successful interactive
authentication and read it at every subsequent `/authorize` request. The
cookie is:

- **Path-scoped to `/t/{slug}`** — the URL path already isolates tenants
  in our routing tree, so the same path-scope automatically gives us
  per-tenant cookies without us inventing a separate naming scheme.
  Browsers will not send `KOTAUTH_SSO` on requests to `/t/widgets/*`
  even though the hostname is the same.
- **HttpOnly** — JavaScript on a tenant page cannot read the cookie, so
  XSS in any single SaaS app cannot exfiltrate it.
- **Signed via `EncryptionService.signCookie`** — same HMAC primitive
  we already use for `KOTAUTH_AUTH_CONTEXT`, `KOTAUTH_MFA_PENDING`,
  `KOTAUTH_PORTAL_PKCE`, and the social-login cookies. One signing key
  (`KAUTH_SECRET_KEY`), one verification path, no new key surface.
  The social cookies no longer go by bare names: over https they carry
  the `__Host-` prefix, and each is suffixed with the tenant that minted
  it — `__Host-KOTAUTH_SOCIAL_PENDING_{slug}`,
  `__Host-KOTAUTH_SOCIAL_PENDING_BINDING_{slug}` and, per provider,
  `__Host-KOTAUTH_SOCIAL_STATE_{provider}_{slug}`. `__Host-` forces
  `Path=/`, so the name is what separates tenants there. Over plain
  http the prefix is dropped (it requires `Secure`) and the tenant
  suffix stays.
- **Opaque pipe-delimited payload, version-prefixed** —
  `v1|userId|tenantId|authTime|mfaCompleted|expiresAt`. Not a JWT: it
  never leaves our origin, never gets sent to a relying party, never
  goes through a parser written by anyone else. The `v1` prefix gives
  us a pinch-point for future revisions without breaking deployed
  cookies.
- **`Secure` whenever the deployment is HTTPS** — gated by
  `KAUTH_BASE_URL` starting with `https://` so local dev still works
  unchanged.

### Why HMAC over JWT for the cookie payload

A JWT cookie would be self-describing, but it would also be the third
representation of authentication state — alongside the access token
(JWT) and the database session row. The cookie travels only between
the browser and us, never to an RP. We win nothing from
self-description; we lose ergonomics every time we add a field to the
payload, since JWT libraries make schema migration awkward.

Pipe-delimited + signed is what every other Kotauth cookie uses. It
fits the codebase, it round-trips through `signCookie/verifyCookie`,
and it is trivially decodable in tests.

### `auth_time` is recorded on the cookie, threaded onto the auth code, and emitted on the ID token

The OIDC `auth_time` claim is the moment the End-User most recently
proved their credentials. Three integration points:

1. `setSsoCookie(...)` is called from `completeAuthorizationCodeFlow`
   on every successful login. Each credential type passes its own
   `authTime`:
   - Password / magic-link / social → `Instant.now()` at completion
   - **MFA → moment the second factor was verified, not the moment
     the password was checked.** This matches Keycloak. The user
     experiences the second factor as the boundary that makes them
     "authenticated"; that is what `max_age` should be measured from.
2. `OAuthService.issueAuthorizationCode` accepts `authTime` and persists
   it on `AuthorizationCode.auth_time` (V38 migration).
3. `JwtTokenAdapter.issueUserTokens` accepts `authTime` and writes
   `auth_time` (epoch seconds) onto the ID token. The discovery doc
   advertises `auth_time` in `claims_supported`.

For silent auth, the cookie's `authTime` flows straight through:
SSO cookie → silent `issueAuthorizationCode(..., authTime = sso.authTime)`
→ `AuthorizationCode.authTime` → ID token's `auth_time` claim. The
RP's `max_age` check works against the original authentication moment,
not the moment of the silent-auth round-trip.

### `mfaCompleted` flag — Keycloak parity for required-MFA tenants

The cookie carries a single `mfaCompleted` bit alongside the
identifiers. Silent auth refuses to issue a code if the tenant
currently requires MFA (`mfaPolicy != "optional"`) and the cookie
says `mfaCompleted=false`. This handles the case where an admin
tightens the tenant policy after a user has already authenticated:
the existing cookie is treated as not-yet-MFA and falls back to the
interactive flow, which re-runs the policy and triggers MFA enrollment.

The simpler check `policy == "optional" || mfaCompleted` is
intentionally conservative for `required_admins`-mode non-admin
users — they would also be denied silent auth without MFA. They get
re-prompted for password (which they can satisfy without MFA) and
walk away with a fresh cookie. We pay one round-trip; we don't have
to ship a route-side role lookup.

### `prompt` semantics on GET `/authorize`

OIDC §3.1.2.1 supports a space-separated set:

| Value            | Behavior                                                                                                                |
|------------------|-------------------------------------------------------------------------------------------------------------------------|
| `none`           | Never show UI. Silent-auth via cookie or fail with `error=login_required` redirected back to a **registered** redirect_uri. |
| `login`          | Force re-auth — `clearSsoCookie(slug)` and render the login form even if the cookie is valid.                          |
| `consent`        | Force consent. We have no consent UI yet, so it folds into `login` until that ships.                                    |
| `select_account` | Account picker. Single-account-per-browser today, so identical to `login`.                                              |

Unknown values return `invalid_request`. `none` combined with anything
else returns `invalid_request` (§3.1.2.1: "if `none` is requested with
any other value, an error is returned"). Discovery advertises
`prompt_values_supported`.

### Open-redirect protection is mandatory before any redirect

`prompt=none` failure cannot bounce to a `redirect_uri` that didn't
come from the client's registered list — that would turn `/authorize`
into an open-redirect vector. Phase 3 introduced
`OAuthService.validateRedirectUri(tenantSlug, clientId, redirectUri)`
exactly for this guard, called before any error redirect for
`prompt=none`. Mismatch returns 400 in JSON; we never construct a
redirect with an attacker-supplied URI.

### `id_token_hint` is honored, but signature verification is a follow-up

The route extracts `sub` from the hint via a payload-only base64 decode
and refuses silent auth on mismatch with the cookie's `userId`. We do
**not** verify the signature in v1.8.1 — and this is a deliberate
constraint, not laziness. The worst case from an attacker-supplied
hint is that silent auth falls through to interactive auth, which is
the safe default. A spec-strict full-validation pass is tracked as a
v1.9.x follow-up.

### Logout MUST clear the cookie everywhere

Phase 4 wires `clearSsoCookie(slug)` into:

- `GET /t/{slug}/protocol/openid-connect/logout`
- `POST /t/{slug}/protocol/openid-connect/logout`
- `POST /t/{slug}/account/logout` (portal)

Not clearing here would silently undo the user's logout: their
`KOTAUTH_PORTAL` session is gone, but the next visit to `/account/login`
silent-auths them straight back in. That is exactly the bug a
"why-am-I-still-logged-in" support ticket would describe, and it is
the easiest cookie protocol to get wrong.

### Portal `/login` itself uses `prompt=none` — with a loop break

Phase 5: portal login on first attempt builds the authUrl with
`&prompt=none`. `/callback` notices `error=login_required` and
redirects to `/login?prompt_failed=true`, which omits `prompt=none` on
its second attempt and renders the form. Two server hops, no infinite
redirect, the user sees one form page if they have no SSO cookie and
zero form pages if they do.

### TTL knobs

Two env vars, validated at startup (`60s ≤ ttl ≤ maxTtl`):

```
KAUTH_SSO_SESSION_TTL_SECONDS=86400         # 24h default — Auth0 parity
KAUTH_SSO_SESSION_MAX_TTL_SECONDS=2592000   # 30d operator ceiling
```

The default is 24h. The max is the operator's policy ceiling — the cookie
itself never lives longer than `expiresAt` carried in its own payload, so
the cap can never be silently exceeded by a stale cookie minted before
the operator tightened the policy.

## Consequences

### Positive

- **Cross-app silent SSO works without RP cooperation.** A SaaS app
  receiving `/authorize` does the same call it has always done; the
  cookie is invisible to the RP and travels only between Kotauth and
  the browser.
- **Tenant isolation is structural, not configurational.** Path-scoped
  cookie + path-scoped routes = no cross-tenant cookie leakage by
  construction. Operators cannot misconfigure their way into a
  cross-tenant SSO leak.
- **`auth_time` finally reaches the ID token.** RPs that rely on it for
  step-up flows or session-freshness policies (Auth0/Okta-style apps)
  can adopt Kotauth without the existing pre-feature gap.
- **Logout actually logs the user out.** Both OIDC end-session and
  portal logout wipe the cookie, so silent-auth-back-in is impossible.
- **Failure modes are loud.** Bad redirect_uri → 400. Unknown prompt
  → 400. Invalid `none + login` combination → 400. Silent-auth miss
  with `prompt=none` → `error=login_required` to the registered URI.
  No silent fallthroughs, no half-states.

### Negative — known limitations

1. **`id_token_hint` signatures are not verified in v1.8.1.** The
   payload-only `sub` extraction is good enough to refuse silent auth on
   mismatch, but it is not the full §3.1.2.1 contract. A v1.9.x
   follow-up will add `decodeIdToken` to `TokenPort` and verify against
   the tenant's JWKS.
2. **Silent auth respects `mfaCompleted` but does not look up roles for
   `required_admins`.** Non-admin users on a `required_admins` tenant
   who did not complete MFA will fall through to the interactive flow
   even though policy doesn't actually require their second factor. The
   UX hit is one extra round-trip. Adding the role lookup at the route
   level would couple the silent-auth path to `RoleRepository`; we
   prefer the over-restriction.
3. **Cookie is stateful but does not reference a session row.** A v1.8.1
   silent-auth issuance is anchored on the cookie's `userId/authTime`;
   it does not check whether the user's most recent session has been
   admin-revoked. In practice, an admin who revokes a session and wants
   to make sure the user is logged out should also disable the user
   account; the cookie's `expiresAt` will then prevent silent re-auth
   when the user next tries to authenticate. A "session-bound SSO
   cookie" variant (cookie carries the session id, silent auth verifies
   the session is not revoked) is tracked for v1.10.0 — it pairs
   naturally with the impersonation feature.
4. **No in-cookie audience scoping.** A user who authenticated via
   Client A on tenant `acme` will silent-auth into Client B on tenant
   `acme` — by design (this is the headline feature). Operators who
   need stricter same-tenant per-client isolation should leave the
   cookie disabled at deploy time by setting
   `KAUTH_SSO_SESSION_TTL_SECONDS=0` to suppress writes. (This switch
   ships in v1.9.x; the env var validation rejects 0 in v1.8.1.)

### Neutral

- The cookie payload is intentionally not a JWT — see "Why HMAC over
  JWT" above. Operators inspecting cookies in DevTools see a base64
  blob with a trailing HMAC, identical in shape to every other Kotauth
  cookie.
- The cookie is set even for failures of `issueAuthorizationCode`
  during the post-login redirect (e.g., PKCE missing). The user's
  authentication fact is established; the cookie tags them with that
  fact even when the request that prompted the login can't complete.
  On retry they silent-auth, which is the right outcome.

## Alternatives considered

- **Stateless cookie with no DB session reference** — what we shipped.
  Considered "session-id in cookie + DB lookup on every silent auth"
  and rejected for v1.8.1 because the cookie's `expiresAt` already
  bounds the worst case to a configurable TTL, and adding a DB read
  to silent auth would re-introduce the per-replica session contention
  Phase 2 of v1.8.0 was meant to relieve. Revisit in v1.10.0 alongside
  impersonation.
- **Domain-scoped cookie with embedded tenantId** — rejected because a
  bug in tenantId comparison would silently turn cross-tenant SSO on.
  Path scoping pushes the isolation guarantee into the browser, which
  is harder to bypass than an `if` statement.
- **JWT cookie with custom claims** — rejected for the schema-evolution
  reason above. We would inherit a token format and lose nothing in
  return; the cookie never leaves our origin.
- **Per-(tenant, client) SSO scoping** — rejected as a v1.8.1 default.
  Industry default (Auth0, Keycloak, Okta) is per-tenant, and that is
  the migration path operators expect. Per-client scoping ships in
  v1.9.x as `KAUTH_SSO_SCOPE=per_client` for tenants that explicitly
  need it.
- **Use `prompt=none` in the cookie freshness check instead of a
  `mfaCompleted` flag** — rejected because the freshness model is "is
  the cookie still valid?", not "what did the user do at login time?".
  We wanted `mfaCompleted` to be readable from the cookie alone so the
  silent-auth route doesn't need a separate DB lookup.

## Threat model

- **XSS on a tenant SaaS app.** Cookie is HttpOnly → JavaScript can't
  read it. The attacker can however drive the browser through the
  silent-auth flow themselves, but only against the tenant where they
  already have XSS — they don't gain cross-tenant reach. The
  authorization code they obtain is bound to a redirect_uri they
  don't control.
- **CSRF against `/authorize`.** Silent auth issues a code only to
  registered redirect URIs, and the auth code itself is single-use
  with PKCE. A forged GET `/authorize` from another origin gets the
  attacker no auth code they can use.
- **Cookie theft via lower-layer attack.** Without `HttpOnly` bypass,
  the only realistic theft is by a CA-trusted MITM, which is the same
  class of attack that breaks every Kotauth cookie and the database
  session in transit. We rely on `Secure` + HSTS; this is not unique
  to the SSO cookie.
- **Cross-tenant takeover via cookie reuse.** Path scope prevents the
  browser from sending `KOTAUTH_SSO` across tenants. Even if the
  cookie were sent, the silent-auth check refuses if
  `cookie.tenantId != currentRequestTenantId`. Two layers of defense.
- **Stale cookie after admin policy tightening.** Covered by
  `mfaCompleted` check above. Cookie outlives a policy change → silent
  auth refused → user re-runs interactive flow → new cookie reflects
  the new policy.
- **Open redirect via `prompt=none` failure path.** Mitigated by
  `validateRedirectUri` before any redirect involving query-supplied
  URIs.
