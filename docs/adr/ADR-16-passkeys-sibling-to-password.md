# ADR-16 — Passkeys as sibling to password, not MFA method

**Status:** Accepted (v1.20.0)

## Context

Kotauth already ships a `MfaService` with a `MfaMethod` enum containing a single value (`TOTP`). Historical comments in the code suggested extending that enum with `WEBAUTHN` and dispatching WebAuthn ceremonies through the same service. When brainstorming v1.20.0, we needed to decide whether passkeys plug into the MFA pipeline as a second-factor method, or ship as a first-class sibling to password authentication.

## Decision

Passkeys are a **passwordless-primary sign-in method**, sibling to password. They are NOT wired into `MfaService`.

- New `WebAuthnService` in `domain/service/` — parallel to `AuthService`, not a subordinate of `MfaService`.
- New `webauthn_credentials` table, unrelated to `mfa_enrollments`.
- The `MfaMethod` enum stays `TOTP`-only. The `mfa_enrollments` unique constraint `(user_id, method)` stays intact.
- A user can hold: password + TOTP + N passkeys simultaneously. Password sign-in still triggers the TOTP challenge. Passkey sign-in skips it.
- Passkey authentication sets `mfaCompleted = true` on the SSO cookie — passkey with user-verification is inherently multi-factor per FIDO2 (device possession + user verification).

## Rationale

Layering WebAuthn into `MfaService` required three real refactors:

1. `MfaService.verifyTotp` becomes a method-dispatching supermethod (`when (method) is TOTP -> ...; is WEBAUTHN -> ...`).
2. The `UNIQUE (user_id, method)` constraint on `mfa_enrollments` must drop or be per-method-relaxed — WebAuthn users routinely enroll 3+ credentials (phone, laptop, hardware key).
3. Recovery-code ownership semantics need rework: today codes are per-user; per-method codes would be more useful in a mixed enrollment.

None of these refactors deliver user-visible value beyond what a clean sibling service achieves. The sibling approach:

- Isolates Yubico dependency to one composition seam (`RelyingPartyAdapter`).
- Keeps `MfaService` TOTP-focused and simple.
- Lets FIDO2's inherent "device possession + user verification" property satisfy MFA via the existing SSO cookie `mfaCompleted` flag without any new challenge state.

Per-tenant `mfa_policy=required` semantics stay honored: a passkey sign-in issues an SSO cookie with `mfaCompleted=true`, so the follow-up OAuth code exchange does not redirect to `/mfa-challenge`.

## Consequences

- `MfaService` and `mfa_enrollments` unchanged.
- New adapter package `com.kauth.adapter.webauthn` for Yubico integration; new port `com.kauth.domain.port.RelyingPartyAdapter`.
- New table `webauthn_credentials` (V54) with N-per-user credentials, no unique constraint on `(user_id, method)`.
- Recovery for passkey-only tenants uses the existing magic-link flow (see [ADR-17](ADR-17-smtp-hard-gate-passwordless-tenants.md)).
- CLI reset commands are per-mechanism: `reset-admin-mfa` and `reset-admin-passkeys` are separate.
- Legacy U2F-only hardware keys (no PIN/biometric) provide only single-factor possession and will NOT set `mfaCompleted=true` on the SSO cookie — those users must enroll TOTP as a second factor if `mfa_policy=required`. Modern platform authenticators (Face ID, Touch ID, Windows Hello, Android biometric) always perform user verification and continue to satisfy MFA.
