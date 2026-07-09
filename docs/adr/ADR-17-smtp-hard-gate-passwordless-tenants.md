# ADR-17 — SMTP hard-gate for `passwordLoginDisabled`

**Status:** Accepted (v1.20.0)

## Context

Kotauth v1.20.0 introduces per-tenant `passwordLoginDisabled` (a passkey-only tenant configuration). Users who lose all their passkeys on a passwordless tenant rely on the existing magic-link email flow to re-enter and enroll a new credential. Magic-link recovery requires working SMTP.

If an operator toggles `passwordLoginDisabled=true` on a tenant that has no configured SMTP, every user who subsequently loses their passkey is locked out permanently.

## Decision

When `passwordLoginDisabled=true` is requested on a tenant, both the admin UI and the backend enforce a hard requirement that the tenant has ready SMTP (`tenant.isSmtpReady == true`).

- **Frontend**: the toggle checkbox is disabled with an explanatory hint when `!tenant.isSmtpReady`. The operator cannot activate the state through the UI.
- **Backend**: the workspace security POST handler rejects the state transition with `400 { "error": "SmtpRequired" }` when `passwordLoginDisabled=true` is posted and SMTP is not ready.
- **Downstream admin actions** also enforce the gate: the admin per-user "Reset all passkeys" action rejects with `400 OperatorLockoutBlocked` when the tenant is passwordless-only and SMTP is not ready — resetting all credentials would leave that user unable to sign in AT ALL.

## Rationale

Recovery invariants are non-negotiable for operator UX. This is not a preference — it is an operator-created lockout invariant, identical in shape to the existing guards around `magicLinkEnabled` and `smtpEnabled`. Security-conscious operators may opt into passwordless-only tenants, but they cannot accidentally opt into "no recovery ever."

Making the invariant a soft warning (that the operator could dismiss) doesn't move the responsibility — it just delays the failure. A hard-gate at both UI and backend catches the mistake at the earliest possible moment.

## Consequences

- Admin UI validates `isSmtpReady` before allowing toggle activation and renders an explanatory hint.
- Backend POST handler rejects state transitions with a specific error code (`SmtpRequired`, `OperatorLockoutBlocked`).
- Turning off SMTP on a tenant with `passwordLoginDisabled=true` should also be blocked — flagged as a v1.20.1 follow-up (not enforced in v1.20.0 because SMTP disable requires an explicit separate step).
- The operator guide documents the passkey-only tenant SMTP prerequisites explicitly.

## Related

- ADR-10 (magic-link passwordless sign-in) established the existing SMTP dependency for magic-link.
- [ADR-16](ADR-16-passkeys-sibling-to-password.md) — passkeys architecture.
