# Provisioning workspaces without the console

A deployment can be brought up from configuration alone: a master API key from the environment,
then one API call per workspace. No console session, and no committed backup to import.

## 1. A master key that may create workspaces

Generate a key and its hash:

```bash
java -jar kauth.jar cli hash-api-key --tenant=master
# plaintext: kauth_master_...
# sha256:    3f1c...
```

Give the hash to the server through `KAUTH_BOOTSTRAP_API_KEYS` (or `KAUTH_BOOTSTRAP_API_KEYS_FILE`),
on the `master` workspace with the `tenants:write` scope. Keep the plaintext in your secret store.

```json
[
  {
    "tenant": "master",
    "name": "provisioner",
    "scopes": ["tenants:write"],
    "keyHash": "3f1c..."
  }
]
```

`tenants:write` is honoured only on a master key. A workspace key that holds it is refused.

## 2. Create a workspace

```bash
curl -sS -X POST "$KAUTH_BASE_URL/admin/api/v1/tenants" \
  -H "Authorization: Bearer $KAUTH_PROVISIONER_KEY" \
  -H "Content-Type: application/json" \
  -d '{
        "slug": "acme",
        "displayName": "Acme",
        "registrationEnabled": false,
        "emailVerificationRequired": true
      }'
```

| Field | Required | Default |
|---|---|---|
| `slug` | yes | Lowercase letters, digits and hyphens; `master` is reserved |
| `displayName` | yes | |
| `issuerUrl` | no | Derived from `KAUTH_BASE_URL` |
| `registrationEnabled` | no | `true` |
| `emailVerificationRequired` | no | `false` |

The workspace is created the same way the console creates one, in a single transaction. It has an
active signing key, the built-in `kotauth-portal` client and the `admin` and `user` roles. Its
discovery document, JWKS and token endpoint work straight away, with no restart.

| Status | Meaning |
|---|---|
| `201` | Created; the body is the workspace, in the same shape as `GET /t/{slug}/api/v1/workspace` |
| `400` | The body is not JSON or lacks `slug` / `displayName` |
| `401` | No key, or a key that is not a master key |
| `403` | A master key without `tenants:write` |
| `409` | A workspace with that slug already exists |
| `422` | The slug or display name is invalid |

A `409` makes the call safe to repeat: a provisioning script can create every workspace it needs
on each run and treat `409` as "already there". An existing workspace is never modified.

## 3. Workspace keys

The workspace's own admin API (`/t/{slug}/api/v1`) needs a key of that workspace. Declare it in
`KAUTH_BOOTSTRAP_API_KEYS` next to the master key, with `"awaitTenant": true`:

```json
[
  { "tenant": "master", "name": "provisioner", "scopes": ["tenants:write"], "keyHash": "3f1c..." },
  {
    "tenant": "acme",
    "name": "acme-provisioner",
    "scopes": ["workspace:read", "workspace:write"],
    "keyHash": "9b2e...",
    "awaitTenant": true
  }
]
```

Generate it with `cli hash-api-key --tenant=acme`. An entry marked `awaitTenant` whose workspace
does not exist yet is held at startup instead of stopping the server. The moment step 2 creates
the workspace, or an import brings it in, the key is provisioned in the same transaction. It works
on the next request, with no restart.

Without `awaitTenant`, an entry naming a workspace that does not exist still stops the server at
startup, so a mistyped slug cannot go unnoticed. Once the workspace exists, every startup applies
its entries as usual, `awaitTenant` or not.

## 4. Workspace settings

A workspace key with the `workspace:write` scope changes the workspace's settings through
`PATCH /t/{slug}/api/v1/workspace`. Only the fields in the body change, so a script can state just
what it cares about:

```bash
curl -sS -X PATCH "$KAUTH_BASE_URL/t/acme/api/v1/workspace" \
  -H "Authorization: Bearer $KAUTH_ACME_KEY" \
  -H "Content-Type: application/json" \
  -d '{
        "registrationEnabled": false,
        "mfaPolicy": "required",
        "tokenExpirySeconds": 600,
        "passwordPolicy": { "minLength": 12, "requireNumber": true },
        "lockoutMaxAttempts": 5,
        "defaultLocale": "es",
        "smtp": {
          "enabled": true,
          "host": "smtp.example.com",
          "port": 587,
          "username": "acme",
          "password": "'"$ACME_SMTP_PASSWORD"'",
          "fromAddress": "no-reply@acme.example"
        },
        "signInMethods": { "emailOtp": true, "password": false }
      }'
```

The body covers sign-up (`registrationEnabled`, `emailVerificationRequired`), sign-in methods,
password policy, MFA policy, lockout, token lifetimes, `loginIdentifierMode`, `defaultLocale` and
SMTP. The full list is in the OpenAPI document at `/api/docs`.

- The request runs the same validation, and records the same audit events, as the console's
  settings pages. It is applied in one transaction: if any field is refused (`422`), nothing
  changes.
- `smtp` is applied first. The same request can therefore configure email delivery and turn on
  magic links or email codes, or turn password sign-in off, which both need SMTP to be ready.
- The SMTP password is write-only. It is never returned, and leaving it out keeps the stored one.
  `GET /workspace` reports `emailDeliveryReady` instead of the SMTP settings.
- An unknown field is refused with `400` rather than ignored, so a typo cannot pass as a no-op.
- Sending the same body again is harmless, so the call belongs in the same idempotent script as
  step 2.

## 4. Retire a key

Remove its entry from `KAUTH_BOOTSTRAP_API_KEYS` and restart. At startup the key is disabled and
the log says so:

```
Bootstrap API key 'provisioner' for tenant 'master' — revoked
```

Do this for the `tenants:write` key once the workspaces exist. A revoked key stops authenticating
straight away. It is disabled, not deleted, so its audit trail stays. It is no longer env-managed,
so the console can delete it. Putting the entry back enables the key again with whatever hash the
entry carries.

Unsetting `KAUTH_BOOTSTRAP_API_KEYS` altogether revokes every key it provisioned.
