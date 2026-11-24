# ADR-24 — Sign-in methods as `amr`, multi-factor as `acr`, and step-up through `acr_values`

**Status:** Accepted
**Context:** RFC 8176, OpenID Connect Core §2, §3.1.2.1 and §12.2, OpenID PAPE, RFC 9470

## Context

A relying party could not tell from Kotauth's tokens how the user signed in. Nothing in the ID
token said whether a second factor was used. A client that needed one for a sensitive action had
no way to ask for it. The only lever was the workspace-wide MFA policy. Auth0 emits `amr` with
`mfa` and honors the PAPE multi-factor `acr_values` for step-up. Keycloak maps `acr` to levels of
assurance. Clerk exposes its own `fva` claim. RFC 9470 describes the API side: a resource server
reads `acr` and `auth_time` from the access token and challenges for step-up.

The authorization code only remembered `auth_time`, and a refreshed ID token didn't even keep
that.

## Decision

**`amr` names what the user proved, with RFC 8176 values where one fits:**

| Sign-in | `amr` |
|---|---|
| Password | `pwd` |
| Authenticator app or recovery code (second factor) | adds `otp` and `mfa` |
| Email code, magic link | `otp` |
| Social or OIDC provider | `fed` |
| Passkey bound to one device / synced (backup eligible) | `hwk` / `swk` |
| Passkey with user verification | adds `mfa` (ADR-16) |

`fed` isn't in the RFC 8176 registry. Microsoft Entra and others use it for federated sign-in,
and no registered value says "an external identity provider vouched". An upstream provider's own
`amr` is not trusted or forwarded.

**`acr` is the PAPE multi-factor URI** (`http://schemas.openid.net/pape/policies/2007/06/multi-factor`)
when `amr` contains `mfa`, and absent otherwise. Kotauth has no levels of assurance beyond
"one factor or more than one", so it doesn't invent numeric levels. The ID token carries `amr`
and `acr`. The access token carries `acr` and `auth_time` (RFC 9470), not `amr`, which is for
the client.

**`acr_values` containing the multi-factor URI makes the sign-in owe a second factor**, whatever
the workspace policy:
- A user with one is challenged.
- A user without one sets one up during sign-in, as when the workspace requires it.
- A browser with a single-factor SSO session is asked **only for the second factor**: the session
  already proves the first, so the password page is skipped.
- `prompt=none` that would need interaction answers `login_required`.

Other `acr_values` are ignored, as OIDC allows for voluntary requests.

**The sign-in is kept end to end.**
- The pending challenge and enrollment cookies carry the first factor's `amr`.
- The SSO cookie moves to `v2`, carrying `amr` where `v1` had an MFA bit. `v1` cookies still
  parse: `1` becomes `mfa`, and `0` becomes no methods.
- Authorization codes and sessions store `amr` (V68). Sessions also store `auth_time`, so a
  refreshed ID token reports the original sign-in (OIDC Core §12.2).

**Silent auth asks the MFA gate what the session still owes.** It no longer compares the
workspace policy with the cookie's MFA bit. A factor enrolled since the session started, a client
asking for multi-factor, and `required_admins` all get the same answer as a fresh sign-in.

## Consequences

- Clients and APIs can require multi-factor per request without making the whole workspace
  require it.
- Codes and sessions written before V68, and `v1` SSO cookies without MFA, produce tokens without
  `amr`/`acr` until the user signs in again. Those tokens never claim more than was proven.
- Silent auth for an enrolled user whose SSO session predates their enrollment now challenges
  instead of passing. That is stricter, and it is what a fresh sign-in would do.
- The API email-code flow (`/auth/verify-otp`, `/auth/verify-mfa`) has no `acr_values`. Its codes
  carry `otp`, plus `mfa` when the second factor was verified.
