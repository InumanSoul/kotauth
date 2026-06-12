# ADR-15: Email OTP as a passwordless primitive

**Status:** Accepted (v1.12.0)
**Date:** 2026-05-25
**Supersedes:** —
**Related:** ADR-10 (magic-link passwordless), ADR-14 (impersonation session model)

## Context

v1.12.0 ships an Email OTP passwordless primitive driven by the
`zion-public-bff` onboarding gate but designed as an agnostic IAM feature.
Magic links (ADR-10) already cover the link-click passwordless flow; OTP
fills the cases where magic links don't fit:

- Corporate mail scanners (Safe Links, Proofpoint) prefetch URLs and consume
  the single-use token before the user clicks it.
- The same-browser cookie guard on magic-link consume (`KOTAUTH_AUTH_CONTEXT`)
  fails for users who open the email in a different browser — common on
  mobile.
- OTP gives the user visual progress as they type each digit and works
  identically on every device.

Four questions dominated the design.

1. **Where does the find-or-create surface live, and how do we contain its
   blast radius?** The BFF send-otp endpoint must create the user if missing
   so the SPA never sees a "user not found" branch (would leak enumeration
   via response shape and timing). But find-or-create over an external API
   key is a new account-creation channel.
2. **How does the originating `client_id` flow from send-otp through
   verify-otp into the authorization code?** v1.11.0's lesson: the
   originating client must drive the default-roles grant and the issued
   token's audience.
3. **Constant-time response posture — how do we prevent timing-based
   enumeration of "user exists" vs "user newly created"?**
4. **What's the cross-challenge abuse defence?** Per-challenge attempt cap
   handles brute force on one challenge; the BFF can request unlimited fresh
   challenges otherwise.

## Decision

### A new `EmailOtpService` in `domain/service/`

Not an extension of `UserSelfServiceService` (already 1,100+ lines covering
password reset, email verification, magic links, profile changes). Adding
find-or-create user creation with a separate challenge lifecycle and
BFF-facing response shape would be a clear SRP violation. The OTP feature
has its own token model, repository port, rate-limiting profile, audit
event set, and `originatingClientId` plumbing.

### Find-or-create gated by `SecurityConfig.emailOtpSignupEnabled` (default off)

A new per-tenant `email_otp_signup_enabled` flag on `tenant_security_config`,
default `FALSE`. Existing tenants do not gain a new account-creation surface
on upgrade. When the flag is off and the email doesn't exist, the service
returns `OtpSendResult.Success` with a random challenge handle that never
resolves — the BFF cannot distinguish new from returning.

Newly created users get `passwordHash = User.SENTINEL_PASSWORD_HASH` (same
sentinel as invite-flow and social-login users) and `emailVerified = false`.
The verify-otp success path stamps `emailVerified = true` only after the
code is proven.

### `originatingClientId` persisted on the challenge row

The send-otp request body carries `originatingClientId`. The service
persists it as a column on `email_otp_challenges`. On verify-otp success
it drives:

1. `applyClientDefaultRolesGrant(tenantId, userId, originatingClientId, ...)`
   — extracted in v1.11.0 from `AuthService` and `SocialLoginService`,
   now reused here as a shared top-level helper. All three entry points
   grant the same default-role bundle for the same client.
2. `AuthorizationCode` issuance: the code is bound to the originating
   client and uses `client.redirectUris.first()` as the redirect URI. The
   BFF must have at least one redirect URI registered on its confidential
   client. The BFF echoes that same URI at the standard `/token` exchange.
   Operator configuration requirement, documented here so it is not
   re-discovered as a bug.

### Constant-time padding at the route layer, not the domain layer

The route handler wraps the service call:

```kotlin
val start = System.nanoTime()
emailOtpService.sendOtp(...)
val elapsed = (System.nanoTime() - start) / 1_000_000
val remaining = 800L - elapsed
if (remaining > 0) delay(remaining)
```

`delay()` is suspending — it does not block the Netty I/O thread. The
domain service stays sync and framework-free; the constant-time concern
belongs at the HTTP boundary. The 800ms target must exceed the bcrypt p99
of the find-or-create path; tune for the deployment.

Best-effort guarantee: under CPU pressure the padding may degrade, but
`MessageDigest.isEqual` handles the actual code comparison in constant
time regardless. The padding defends against new-vs-returning enumeration,
not against cryptographic attacks on the code itself.

### Cross-challenge abuse defence reuses the existing lockout machinery

Per-challenge attempt cap is 5; over the cap the challenge is killed.
The cross-challenge guard is a new `failed_otp_challenges` counter on
`users`, separate from `failed_login_attempts` so the two attack surfaces
have independent thresholds and analytics. When the counter reaches
`SecurityConfig.emailOtpLockoutThreshold` (default 5, configurable per
tenant), the existing `locked_until` window is set using the tenant's
`lockoutDurationMinutes` config. The same `EmailPort.sendAccountLockedEmail`
template runs — adding OTP abuse as a reason is a one-line copy change.
Audit event `EMAIL_OTP_LOCKOUT` records `reason=verify_otp_abuse` and the
threshold value.

The lock never leaks back to the BFF: locked users get a uniform
`too_many_attempts` response.

### `from_email` stays operator-controlled

Per-tenant `tenant_email_branding` carries five overridable fields:
`brand_name`, `brand_color_hex`, `brand_logo_url`, `support_email`,
`from_display_name`. The envelope sender (`smtp_from_address`) is
deliberately NOT here — DKIM/SPF/DMARC alignment requires the sender
domain to match the deployment's SMTP credentials and DNS records. A
tenant-settable `from_email` would immediately break deliverability for
any tenant that put in an address outside the sending domain, and create
a spoofing surface.

Branding is composed into the existing `Tenant` aggregate as
`Tenant.emailBranding: TenantEmailBranding?`. The single `buildEmailHtml`
function in `SmtpEmailAdapter` resolves brand name/color/logo from
`tenant.emailBranding` with fallback to `tenant.displayName` and
`tenant.theme`. All six existing email templates (verification, magic
link, password reset, account locked, invite, password changed) inherit
the branding for free — no per-template wiring.

### Boot-time API keys via `KAUTH_BOOTSTRAP_API_KEYS`

A new JSON env var parsed at boot:

```json
[
  {
    "tenant": "zion",
    "name": "zion-public-bff",
    "scopes": ["auth:send-otp", "auth:verify-otp"],
    "keyHash": "<sha256-hex>"
  }
]
```

Idempotent upsert by `(tenant_id, name)` — a new V47 unique constraint.
Rotating a key = edit the hash in the env var. The hash format stays
SHA-256 (consistent with V19 design; 256-bit random tokens, brute force
is not the threat). Bootstrapped rows carry a `bootstrap_name` column so
the admin UI distinguishes env-managed from manually-created keys and
refuses revoke/delete (env-edit is the only mutation path).

Unknown tenant slug or unknown scope at boot causes a fail-fast exit —
a typo cannot silently leave a partner BFF without its key.

### `EmailTemplatePort` deferred

The new OTP template is built as Kotlin string concatenation in
`SmtpEmailAdapter`, consistent with the six templates already there. A
`EmailTemplatePort` with a volume-mount adapter (mirroring the i18n
strategy from ADR-11) is tempting but premature — no operator has asked
for it yet, and introducing the abstraction adds carrying cost without a
second implementer to validate the shape. Revisit when one of: an operator
requests Handlebars/Mustache support, a second template surface emerges,
or `SmtpEmailAdapter` crosses ~800 lines from accumulated templates.

## Alternatives rejected

**Email OTP via the magic-link service.** Reusing `initiateMagicLink` /
`consumeMagicLink` was tempting but the consume path is tied to the
single-use token model in `password_reset_tokens` with a `MAGIC_LINK`
purpose discriminator. Adding `EMAIL_OTP` as a fourth purpose would force
the same code-vs-link branching into a service that was designed for the
link case. The find-or-create semantics, opaque-challenge-handle model,
attempt counter, and constant-time response shape are all OTP-specific.

**A new domain service with its own dedicated rate-limiter abstraction.**
Considered carving out a `PasswordlessChannelRateLimiter` to share between
magic links and OTP. Rejected — the existing `RateLimiterPort` is the
right granularity. Two named instances (`otp_email`, `otp_ip`) wired in
`ServiceGraph` are all that's needed.

**Option B: documented `INSERT INTO api_keys ...` SQL bootstrap.** Tempting
because it's zero code, but couples every integrator's seed file to
Kotauth's internal schema. Option A (`KAUTH_BOOTSTRAP_API_KEYS` env var)
gives a stable public interface and survives column additions in the
`api_keys` table.

**Interactive hosted-login OTP page in v1.12.0.** Out of scope — the
admin API surface is what the BFF onboarding gate needs, and the hosted
page requires its own view layer, session state for the
challenge-id-between-steps, MFA interaction decisions, and login-method
picker placement. Deferred to v1.13.0; `EmailOtpService` was deliberately
designed consumer-agnostic so the hosted page wires in without
re-plumbing.

## Consequences

- v1.12.0 ships ~2k LOC across three coherent primitives (Email OTP,
  bootstrap keys, tenant email branding). All three serve future Kotauth
  consumers, not just the Zion BFF that triggered the work.
- The find-or-create flag default-off means new tenants must opt in.
  Documented in `docs/internal/V1_12_0_PLAN.md` and the admin UI security
  settings.
- The originating-client redirect-URI requirement is a real operator
  footgun — a misconfigured BFF client (no redirect URI) makes the verify
  endpoint return 422 `invalid_client`. Documented in this ADR and in the
  v1.13.0 hosted-page work as a follow-up to validate at client-creation
  time.
- All existing emails pick up tenant branding automatically. Operators
  who previously customized only the SMTP `from_name` may find their
  emails look subtly different on first save — the `from_display_name`
  field falls back to the SMTP `from_name` so this is a no-op in practice.
- Constant-time padding at 800ms means every send-otp request takes at
  least that long. Acceptable for an onboarding flow; would be unacceptable
  on a hot login path. The hosted-page (v1.13) work should reconsider the
  budget when the same primitive serves browser-driven UX.

## v1.13.0 addendum — hosted login-page Email OTP

The admin-API surface from v1.12.0 served BFF-driven onboarding. v1.13.0
ships the browser-driven equivalent: a two-step hosted login page (enter
email → enter 6-digit code). `EmailOtpService` was deliberately built
consumer-agnostic; the v1.13.0 work added only route + view +
session-state.

**Decisions specific to the hosted-page flow:**

- **Two-page flow with separate URLs** — Auth0 / Stytch / Clerk standard;
  step 1 captures the email for analytics even if step 2 is abandoned.
- **Single 6-digit input** with `inputmode=numeric pattern=\d{6}
  autocomplete=one-time-code` — the 6-box pattern breaks paste on Android
  and prevents iOS SMS autofill (which only fires on a single field).
- **60-second resend cooldown** server-enforced — Auth0 / Stytch
  benchmark.
- **Constant-time budget reduced from 800ms → 200ms** for the browser
  path. 800ms makes the hosted UX feel broken. 200ms still defeats the
  timing-enumeration vector and is unobservable in browser UX.
- **No same-browser guard** — cross-device is the failure mode OTP was
  introduced to solve. Magic-link's `KOTAUTH_AUTH_CONTEXT` cookie guard
  is intentionally not applied to OTP consume.
- **Session state via `KOTAUTH_AUTH_CONTEXT` cookie extension** — added
  two fields (`otpChallengeId`, `otpEmail`) at positions 10 and 11.
  Backward-compatible: legacy 9-field cookies continue to parse via
  `parts.size < 9 → null`. A second cookie would have been cleaner in
  isolation but doubles the auth-flow cookie surface for no functional
  win.
- **MFA chain reuses `completeAuthorizationCodeFlow(mfaCompleted=false)`**
  — same hook magic-link uses. TOTP-enrolled users still get challenged.
- **`OtpVerifyResult.Success` carries `userId`** so the hosted route can
  enter the MFA-aware completion helper directly without round-tripping
  through `AuthorizationCodeRepository.findByCode`. Existing admin-API
  consumers ignore the new field.
- **`emailOtpLoginEnabled` is a separate toggle from
  `emailOtpSignupEnabled`** — they govern different security axes.
  Login-OTP is a passwordless option for existing users; signup-OTP is
  an account-creation channel for BFFs. Coupling them would be wrong.
- **Picker placement: drawer next to magic-link** — when
  `passwordLoginEnabled=true`, OTP sits in a "passwordless options"
  drawer alongside the magic-link link, not as a primary CTA. When
  `passwordLoginEnabled=false`, the magic-link form is the primary and
  OTP is a footer link below it. Hard-coded in `AuthView` (no plugin
  registry).

**Operator footgun closed in v1.13:** the OTP cross-challenge lockout
threshold field was previously disabled based on `emailOtpSignupEnabled`
only. Enabling login-OTP without signup would have left the login flow
without a brute-force guard. The disable condition now checks both
toggles.
