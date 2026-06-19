# Audit Log Operations

## Overview

The `audit_log` table records security events (logins, token issuance, admin actions, etc.)
across all tenants. As of v1.19.0, each row carries an HMAC-SHA256 hash chaining it to the
previous row in the same tenant, forming a tamper-evident ledger.

### Chain columns

| Column | Type | Description |
|---|---|---|
| `prev_hash` | `BYTEA` | `row_hash` of the immediately preceding row for the same `tenant_id`. `NULL` for the first row per tenant. |
| `row_hash` | `BYTEA` | HMAC-SHA256 over the canonical row string (see `AuditChainHasher.kt`). |
| `chain_key_id` | `VARCHAR(32)` | First 8 hex chars of `sha256(auditMacKey)` — identifies which `KAUTH_SECRET_KEY` signed the chain. |

The MAC key is derived once at startup:

```
auditMacKey = sha256("$KAUTH_SECRET_KEY|kauth/audit-log/v1")
```

Rows inserted before v1.19.0 have `NULL` in all three columns ("pre-chain" rows). The
verifier treats them as a chain reset — they do not constitute a break.

---

## Recommended Postgres Role Separation

Restrict the application user to `INSERT` and `SELECT` only on `audit_log`. A separate
maintenance role retains `UPDATE` and `DELETE` for legitimate backups and schema migrations.

```sql
-- Restrict the application user to INSERT and SELECT only on audit_log.
-- A separate maintenance role retains UPDATE and DELETE for legitimate backups
-- and schema migrations.
REVOKE UPDATE, DELETE ON audit_log FROM kauth_app;
GRANT SELECT, INSERT ON audit_log TO kauth_app;

-- Create a maintenance role for backups and migrations:
CREATE ROLE kauth_maintenance;
GRANT SELECT, INSERT, UPDATE, DELETE ON audit_log TO kauth_maintenance;
```

This does not prevent all tampering (a superuser or DBA can always act directly), but it
closes the common case of application-level SQL injection or a compromised application
credential being used to delete or alter rows.

**Do not add a Postgres trigger to hard-block `UPDATE`/`DELETE`** — that would prevent
`pg_dump`/`pg_restore` during legitimate backup and restore operations.

---

## Verifying Chain Integrity

Use the `verify-audit-chain` CLI command to walk the chain and detect divergences:

```bash
# Verify all tenants
KAUTH_SECRET_KEY=... DB_URL=... DB_USER=... DB_PASSWORD=... \
  java -jar kauth.jar cli verify-audit-chain

# Verify a single tenant
KAUTH_SECRET_KEY=... DB_URL=... DB_USER=... DB_PASSWORD=... \
  java -jar kauth.jar cli verify-audit-chain --tenant=acme

# Verify from a specific row onwards
KAUTH_SECRET_KEY=... DB_URL=... DB_USER=... DB_PASSWORD=... \
  java -jar kauth.jar cli verify-audit-chain --tenant=acme --from-id=1000

# Quiet mode (no output on success; exit 1 only on divergence)
KAUTH_SECRET_KEY=... DB_URL=... DB_USER=... DB_PASSWORD=... \
  java -jar kauth.jar cli verify-audit-chain --quiet
```

Exit codes:

| Code | Meaning |
|---|---|
| 0 | Chain is intact for all verified tenants |
| 1 | At least one divergence detected — see stderr for details |
| 2 | No rows found (possible config error — check `DB_URL` and tenant slug) |

### Pre-chain rows

Rows with `row_hash IS NULL` were written before v1.19.0. The verifier prints:

```
tenant=acme id=123: pre-chain (no row_hash, skipped)
```

and resets the previous-hash tracking. This is expected and does not signal tampering.
The chain resumes cleanly from the first post-upgrade row.

---

## Rotating KAUTH_SECRET_KEY

If you rotate `KAUTH_SECRET_KEY`, the new key will derive a different `auditMacKey`. Rows
written under the old key will no longer verify with the new key — `verify-audit-chain`
will report every post-key-rotation row as a divergence when run with the old key.

The `chain_key_id` column identifies which key signed each row. If you are auditing across
a key rotation, run `verify-audit-chain` twice: once with the old key for rows matching the
old `chain_key_id`, and once with the new key for newer rows.
