# Email OTP for backend clients

Kotauth's Email OTP API lets a trusted backend start and verify a passwordless
email challenge. The backend authenticates with API keys scoped for
`auth:send-otp` and `auth:verify-otp`; browser clients should call the backend,
not Kotauth's API directly.

## Requesting an API audience

`POST /t/{tenantSlug}/api/v1/auth/send-otp` accepts an optional `resources`
array alongside the email address and originating OAuth client:

```json
{
  "email": "applicant@example.com",
  "originatingClientId": "onboarding-bff",
  "resources": ["https://api.example.com/onboarding"]
}
```

Each value is an RFC 8707 resource indicator. Kotauth normalizes and stores the
list with the OTP challenge so `/auth/verify-otp` can bind it to the resulting
single-use authorization code. When the backend exchanges that code at the
token endpoint, the access token's `aud` targets the requested resource while
the ID token remains bound to `originatingClientId`.

Every requested resource must be enabled in the same workspace and authorized
for `originatingClientId`. Verification returns `invalid_client` and does not
issue an authorization code if any resource is unknown, disabled, or not
authorized for that client.

Omit `resources` to retain the legacy Email OTP behavior. The field defaults to
an empty list, so existing integrations remain compatible.

## Users with a second factor

When the user has TOTP enrolled, `/auth/verify-otp` issues no authorization
code. It answers `403` with a body like:

```json
{
  "type": "https://kotauth.dev/errors/403",
  "title": "MFA required",
  "status": 403,
  "detail": "Send a TOTP or recovery code with this mfaToken to verify-mfa.",
  "error": "mfa_required",
  "mfaToken": "…",
  "mfaRequirements": ["otp", "recovery-code"],
  "expiresAt": "2026-11-20T21:45:00Z"
}
```

Ask the user for a code from their authenticator app (or a recovery code) and
send it with the token:

```json
POST /t/{tenantSlug}/api/v1/auth/verify-mfa
{ "mfaToken": "…", "code": "123456" }
```

The success response is the same as `/auth/verify-otp`'s. The token expires five
minutes after issue and only works in the workspace that issued it; wrong codes
count toward the user's TOTP lockout. If the workspace requires MFA and the user
has not enrolled, `/auth/verify-otp` answers `403` with
`mfa_enrollment_required`; the user must enroll in the account portal first.

This mirrors Auth0's `mfa_required` / `mfa_token` exchange, so the second factor
can never be skipped by signing in through the API instead of the hosted pages.

See [ADR-15](../adr/ADR-15-email-otp-passwordless-primitive.md) for the Email
OTP security model and challenge lifecycle.
