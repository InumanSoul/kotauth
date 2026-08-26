# ADR-21 — Just-in-time provisioning creates accounts and never adopts them

**Status:** Accepted

## Context

Brokering sign-in through an external OpenID Connect provider leaves one question the broker itself
cannot answer: what happens the first time someone arrives who has no account here.

Before this phase the answer was the registration completion page. The callback resolved an existing
identity — a `social_accounts` row matching `(tenant, provider, providerUserId)`, or a local user with
the same verified email — and when neither matched, the person was asked to choose a username and
confirm. That is correct and it is also the thing an operator rolling out SSO to a few hundred people
most wants gone: every one of them stops at a form asking them to name themselves at a workspace their
identity provider has already vouched for.

Making that form disappear means letting an assertion from an external system create a local account.
The assertion is a set of claims in an ID token: a subject, usually an email, and — if the provider
sends it — an `email_verified` boolean. None of that is proof the person owns the address. A provider
is free to assert any address it likes, and on a multi-tenant instance whoever administers *any*
workspace can point it at an issuer they control. So the question is not "should first sign-in create
an account" but "on what evidence, and what may that account already be".

## Decision

**A brokered first sign-in creates a local account when three conditions all hold, and the created
account is always a new one.**

The three conditions, checked in `JitProvisioningService.provision`:

1. **The provider has JIT switched on** — `identity_providers.jit_enabled`, per provider key, default
   false. An operator turns it on for the issuer they run, not for brokering in general.
2. **The provider asserts the email is verified** — `SocialUserProfile.emailVerified`. An unverified
   address is refused; the provider is being asked to stand behind the claim, and one that will not is
   not a basis for creating anything.
3. **The email's domain is on that provider's allowlist** — `identity_providers.jit_allowed_domains`,
   matched **exactly** and case-insensitively against the whole domain. **An empty list is the feature
   off, never a wildcard**, so the toggle alone provisions nobody.

The domain test is deliberately not a suffix test. `endsWith("oriana.com.py")` also accepts
`evil-oriana.com.py`, a domain anyone can register for the price of a registration. The address is
also rejected outright unless it contains exactly one `@`, so `a@evil.example@oriana.com.py` cannot
read as the allowed domain.

**The gate sits after existing-user resolution and only ever calls `save`.** It is reached from the
`ExistingUserMatch.None` branch of `SocialLoginService.handleCallback` — the branch that has already
established that no `social_accounts` row and no local email match this identity. It has no update
path, no link path, and no way to reach a user that already exists.

What a provisioned account looks like:

- **`username` is the email address.** SCIM's `userName` is the email too, so a provisioning client
  later wired to the same directory finds this person instead of creating a parallel duplicate.
- **`external_id` is left null.** It is SCIM's correlation key and belongs to whatever provisions over
  SCIM; the provider identity is recorded in `social_accounts` instead. Both subsystems can hold the
  same person without fighting over one column.
- `password_hash` is the sentinel — there is no local password until the person sets one — `email_verified`
  is true, and the originating client's default roles are granted exactly as a completed registration
  would grant them.
- Every creation writes a `JIT_USER_PROVISIONED` audit event.

**A refusal is explained to the person and recorded for the operator.** The page says authentication
succeeded, that the workspace has not granted the account access, and which of the two conditions
failed — because "not verified" is something the person can fix at their provider and "domain not
allowed" is something only an administrator can. The audit row carries the provider, the reason, the
email's **domain**, and a `reference`: a truncated digest over `(tenant, provider, subject)`, stable so
six retries read as one person, reversible to nothing. No address, no provider subject, no token, no
authorization code, no client secret, no PKCE verifier. `BrokeredSignInFailure` is the single
definition of those keys, written by the gate and by the callback route, read by the diagnostics panel.

## Rationale

### Why `tenant.registrationEnabled` is not a fourth condition

It would be a natural fourth gate, and it is wrong in both directions.

Requiring it would mean an operator who wants *restricted* provisioning — this issuer, these domains,
verified addresses only — first has to switch on *open self-registration* for the whole workspace. That
is a wider door opened in order to use a narrower one, and the wider one stays open.

The mirror is just as bad: JIT must not override `registrationEnabled` either. So a refused gate does
not end the flow where sign-up is open — it falls through to the ordinary registration completion page,
and the refusal is recorded either way. Only where sign-up is closed is "not permitted here" the whole
truth; offering a sign-up form to someone just told they are refused is incoherent, and hiding an
available sign-up form because a convenience feature declined is a silent narrowing of a setting the
operator chose independently.

The gate governs **auto-creation**, not all account creation. Two doors, two switches.

### Why JIT never adopts an existing account, and what that costs

The tempting behaviour is the one the market ships: an asserted verified email that matches an existing
local user gets linked to it, and the person signs in to the account they already had. Keycloak, Zitadel
and Okta all offer some form of verified auto-link. This implementation does not, and the reason is that
the link is the takeover.

A provider willing to assert `verified: true` for an address it does not own is, under an auto-link
rule, a way to sign in as the owner of that address. On a single-tenant install with one trusted
corporate IdP that is a manageable assumption. On a multi-tenant instance where administering one
workspace is enough to register an issuer, it is a privilege-escalation primitive, and the earlier
fixes in this phase — binding `state` to the browser, scoping the social cookies to their workspace —
were all closing variants of exactly that shape.

**The consequence has to be written down rather than discovered from a support ticket.** A person who
already has a local account and later arrives with the same verified email from an identity provider
gets a refusal or a registration page, never their existing account. **There is no self-service path
out of that state.** An administrator reconciles the two records — deleting or renaming one, or linking
the identity deliberately. Anyone planning a migration where existing local users will start arriving
through an IdP should plan that reconciliation as part of the rollout, not meet it afterwards.

This is a deliberate asymmetry: creation is automatic, adoption is manual. Automatic creation of a
brand-new record risks a duplicate account, which is annoying and reversible. Automatic adoption of an
existing record risks handing over an account, which is neither.

### Why the empty allowlist is off rather than open

The alternative reading — no list means every domain — makes the most dangerous configuration the one
that requires the least typing, and makes the toggle alone sufficient to create accounts for anyone any
issuer will vouch for. The admin form says so where an operator can see it, and the domain list is
rendered as a chip grid rather than a free-text field so removing a domain is a click rather than an
edit to a delimited string.

### Why the API distinguishes absent from empty

`jitEnabled` and `jitAllowedDomains` are nullable on the upsert request. Absent means "the body did not
mention this" and keeps what is stored; `"jitAllowedDomains": []` is the explicit "stop creating
accounts automatically". Conflating them would let an unrelated update — renaming a client, rotating a
secret — silently switch off provisioning for a whole domain list, or silently switch it on. `enabled`
is deliberately not treated this way: its default of `true` is a documented contract, not a silent one.

### Why Test discovery states what it did not verify

The provider form can fetch an issuer's discovery document, report the resolved endpoints and count the
verification keys at the JWKS URI. That is half of "is this provider set up correctly", and a green
result that did not say so would be read as the whole of it.

The other half is not observable from this side at all. A discovery document says nothing about which
redirect URIs are registered against a client, and nothing in a probe authenticates as the client. A
callback URL the provider does not recognise is refused **at the provider**, during a real sign-in,
long after the test said the endpoints resolve. So the result panel has a "What this test did not
verify" section naming the redirect URI and the client credentials, and prints the exact callback URL
to register — built from the same function the login flow builds it from, so the two cannot drift.

The failures that only a real sign-in reveals get their own place to appear: a Recent sign-in failures
panel on the provider, fed by the same `SOCIAL_LOGIN_FAILED` rows the gate writes. An error the provider
returns is recorded there too, but only for a `state` this instance signed for this tenant and provider
— the callback is unauthenticated, and without that check anyone could fill an operator's diagnostics
panel with whatever reason they liked.

### What may be claimed about any named provider

**No identity provider has been verified against a live tenant by this work.** The honest claim is
conformance to the specifications the providers publish, plus normalising the deviations they document.
Nothing — the changelog, the README, the operator-facing docs, the admin copy — may describe a provider
as verified, certified, or known-working until an end-to-end pass against a real tenant has happened.
This is the same rule ADR-20 records for the SCIM dialect fixtures, for the same reason: realistic
plumbing invites a reader to assume evidence that does not exist.

## Consequences

- **Nothing changes for a workspace that does not switch JIT on.** `jit_enabled` defaults false and
  the allowlist defaults empty (V63, already required for brokering itself), so the callback behaves
  exactly as it did: existing identity resolved, otherwise the registration completion page.
- A JIT-provisioned account has **no password**. The person signs in through the provider; the admin
  UI badges the record accordingly, with its own wording rather than the SCIM badge's, because no sync
  ever runs over a brokered account and "may be overwritten on its next sync" would send an operator
  looking for a sync that does not exist.
- **A first sign-in and a duplicate are the same event to an operator until they look.** Someone whose
  local account predates the provider will appear as a refusal or a second registration, not as a
  link. The diagnostics panel is where that shows up, and it shows the email domain and a reference,
  never the address — deliberately, so the panel does not become a list of everyone who was turned away.
- Group and role claim mapping is **not** part of this. A provisioned user gets the originating
  client's default roles and nothing derived from the token's claims. Mapping needs its own design pass
  for claim shapes and precedence, and guessing at it here would set a shape that is hard to change.
- The Auth Methods grid gained **one aggregate row** for brokered providers rather than a row each. A
  provider key is an open string, so there is no `MethodKey` per provider, and a grid that grew a row
  per configured issuer would stop being the sign-in method grid.
- Adding a further trust condition later means adding it to `provision` and to the two audit reason
  codes. The refusal shape is a closed set in `BrokeredSignInFailure` precisely so a new reason cannot
  be invented at a call site and leave the diagnostics panel rendering an unrecognised string.

## Related

- ADR-01 (hexagonal architecture) — `JitProvisioningService`, `BrokeredSignInFailure` and
  `IdentityProviderProbeService` live in `domain/` and import no framework.
- ADR-02 (Flyway migrations) — the JIT columns ship in V63 alongside the rest of the brokering
  configuration; this decision adds no migration of its own.
- ADR-03 (audit log split) — provisioning and refusal are audit events, and the refusal record is
  written whether or not the person then falls through to registration.
- ADR-06 (`AdminResult<T>`) — the discovery probe returns a sealed result; it never throws for an
  unreachable issuer.
- ADR-20 (SCIM dialects) — `external_id` stays SCIM's correlation key, and the "documented, not
  verified" rule about naming providers is the same rule.
