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

See [ADR-15](../adr/ADR-15-email-otp-passwordless-primitive.md) for the Email
OTP security model and challenge lifecycle.
