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

Once a workspace exists, a `KAUTH_BOOTSTRAP_API_KEYS` entry can name it, and the workspace's own
admin API (`/t/{slug}/api/v1`) takes over from there. Bootstrap entries are applied at startup and
exit the process for a slug that does not exist yet, so add a workspace's keys after the call that
creates it, and restart to apply them.
