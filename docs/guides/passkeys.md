# Passkeys (WebAuthn) in Kotauth

Kotauth v1.20.0 adds passkeys as a passwordless-primary sign-in method.

## What's a passkey?

A cryptographic credential bound to your device (Face ID, Touch ID, Windows Hello, Android biometrics) or a hardware key (YubiKey, Feitian, etc.). Passkeys replace passwords for daily sign-in and are phishing-resistant by design.

Kotauth's implementation uses the [Yubico WebAuthn server-side library](https://developers.yubico.com/java-webauthn-server/) and supports:

- Platform authenticators (Face ID, Touch ID, Windows Hello, Android biometrics)
- Roaming authenticators (YubiKey, hardware security keys)
- Cross-device flows via QR code (hybrid transport)

## Enabling passkeys per tenant

The Sign-in Methods grid on the workspace Security Policy page (`/admin/workspaces/<slug>/settings/security`) controls all authentication methods in one place. Each row represents a method (Password, Passkey, Email magic link, Email OTP, Google, GitHub) with an enable/disable toggle. Methods that require SMTP or OAuth credentials show a locked row with a "Set up first" prompt when the prerequisite is missing.

To make a tenant passwordless-only, disable the **Password** row. The backend enforces the SMTP hard-gate: the toggle is rejected with `SmtpRequired` if SMTP is not configured (see [ADR-17](../adr/ADR-17-smtp-hard-gate-passwordless-tenants.md)).

### SMTP is required to disable password sign-in

Without SMTP, users who lose all their passkeys cannot recover. The admin UI disables the toggle when SMTP is not configured; the backend rejects the change with `400 SmtpRequired`. See [ADR-17](../adr/ADR-17-smtp-hard-gate-passwordless-tenants.md).

## User enrollment

Users enroll passkeys via the portal at **Security → Passkeys** (`/t/<slug>/account/passkeys`). Each device is a separate credential — enrolling on a phone does not cover the laptop; enroll multiple.

## Recovery when a user loses all passkeys

**When password sign-in is enabled** (default):
- Users sign in with password (and TOTP if configured).

**When password sign-in is disabled** (passwordless tenant):
- Users click "Get a magic link" on the login page.
- Verify the email → land on the post-magic-link enrollment page.
- Enroll a new passkey on the next device.

## Cross-origin note

Passkeys are bound to the RP ID derived from `KAUTH_BASE_URL`. Users who enrolled under `staging.example.com` cannot use those passkeys on `prod.example.com`. Communicate origin changes clearly to users.

## Sign-in UX

The hosted login page renders two signals when `passkeysEnabled` is on:

1. `autocomplete="username webauthn"` on the username field → browsers surface enrolled passkeys via conditional-mediation autofill (Chrome, Safari, Firefox).
2. An explicit "Sign in with a passkey" button below the password field → always available for users who missed the autofill hint.

Passkey authentication inherently satisfies MFA per FIDO2 (device possession + user verification). Users with `mfa_policy=required` who sign in with a passkey skip the TOTP challenge.

## Admin management

Operators can view and revoke individual credentials from the per-user detail page (reachable via **Security → Passkeys** in the admin sidebar, then the user row):

- List enrolled passkeys with device (via AAGUID lookup) and last-used date.
- Revoke a single credential.
- Reset all passkeys for a user (blocked when the tenant is passwordless-only and SMTP is not ready — see ADR-17).
- Reset MFA (TOTP enrollments + recovery codes) — also on the same page.

CLI equivalents for master-tenant emergency recovery:

```bash
java -jar kauth.jar cli reset-admin-passkeys --username=admin
java -jar kauth.jar cli reset-admin-mfa --username=admin
```

## Backup and restore

Passkey credentials are **not included in backups**. After a restore to a different origin, users must re-enroll. This is the correct behavior for cryptographic device binding — restoring credentials to a new RP ID would break the signature chain.

## Architecture

- Domain service: `com.kauth.domain.service.WebAuthnService` (parallel to `AuthService`, not layered on `MfaService`).
- Yubico integration confined to `com.kauth.adapter.webauthn.*` behind the `RelyingPartyAdapter` port.
- Multi-tenant RP ID: single global RP ID derived from the origin of `KAUTH_BASE_URL`. Slug routing (`/t/<slug>`) does not create per-tenant RP IDs.
- User handle: deterministic SHA-256 of `${tenantId}:${userId}:${KAUTH_SECRET_KEY}` — no PII exposed to authenticators, and stable across DB restores.

See [ADR-16](../adr/ADR-16-passkeys-sibling-to-password.md) for the design rationale.

## Known limitations (v1.20.1)

- **Enterprise attestation is off.** All authenticators are accepted (`attestation=none`). FIDO MDS blocklist verification is a future candidate.
- **User verification is `preferred`.** Modern platform authenticators always provide it; older U2F-only hardware keys can enroll without. Per-tenant `required` override is a future candidate.
- **AAGUID display table is bundled.** ~11 common device names ship in `webauthn/aaguid-names.json`. Unknown devices render as "Unknown authenticator". Future release: sync from Yubico Metadata Service.
