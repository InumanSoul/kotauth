-- V66: Normalize usernames to trimmed, lowercased [a-z0-9._@+-]+ form (v1.25.0 hardening).
--
-- New product rule: usernames are ALWAYS stored normalized — trimmed, lowercased, and
-- matching [a-z0-9._@+-]+. Historically AuthService.register performed no username format
-- validation at all, so rows exist that are mixed case or contain arbitrary characters
-- (e.g. "John Doe"). This migration, in order:
--   1. Aborts if normalizing would collide two rows within the same tenant — an operator
--      must de-duplicate those by hand. Silently merging or dropping identity rows is not
--      an acceptable failure mode.
--   2. Rewrites every existing username to its normalized form: lowercase; each run of
--      forbidden characters collapsed to a single '.'; leading/trailing '.', '_', '-' stripped.
--   3. Adds a unique index on (tenant_id, lower(username)) to enforce the rule going forward.
--      This also serves UserRepository.findByUsernameIgnoreCase — no separate index needed.
--
-- On CONCURRENTLY: verified against the running kotauth-db container (Flyway 12.11,
-- Postgres 15) that this project's migrations run inside a transaction by default —
-- DatabaseFactory.init() calls Flyway.configure() with no per-script .conf file and no
-- `mixed(true)`, and there is no repo-wide flyway.conf setting it either. CREATE INDEX
-- CONCURRENTLY cannot run inside a transaction block, so it is not usable here. This uses
-- a plain CREATE UNIQUE INDEX instead, which takes a brief write lock on `users` for the
-- duration of the build — acceptable for this table's size.

-- Session-local helper so the normalization expression is defined exactly once. Explicitly
-- scoped to pg_temp so it cannot leak into the permanent schema even if a later statement
-- in this script fails before the DROP FUNCTION below runs (the whole script still rolls
-- back as one transaction in that case, but this keeps the intent explicit either way).
CREATE FUNCTION pg_temp.kauth_normalize_username(raw text) RETURNS text AS $$
    SELECT regexp_replace(
        regexp_replace(
            regexp_replace(lower(trim(raw)), '[^a-z0-9._@+-]+', '.', 'g'),
            '^[._-]+', ''
        ),
        '[._-]+$', ''
    )
$$ LANGUAGE sql IMMUTABLE;

-- Step 1: refuse to proceed if normalization would collide within a tenant.
DO $$
DECLARE
    collision RECORD;
BEGIN
    SELECT tenant_id, normalized, COUNT(*) AS cnt
    INTO collision
    FROM (
        SELECT tenant_id, pg_temp.kauth_normalize_username(username) AS normalized
        FROM users
    ) n
    GROUP BY tenant_id, normalized
    HAVING COUNT(*) > 1
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'V66 aborted: tenant % has % existing usernames that would all normalize to ''%''. '
            'De-duplicate them by hand (rename or merge the accounts) before re-running this migration.',
            collision.tenant_id, collision.cnt, collision.normalized;
    END IF;
END $$;

-- Step 2: normalize every existing username.
UPDATE users
SET username = pg_temp.kauth_normalize_username(username)
WHERE username <> pg_temp.kauth_normalize_username(username);

DROP FUNCTION pg_temp.kauth_normalize_username(text);

-- Step 3: enforce the rule going forward. Cannot use CONCURRENTLY inside Flyway's
-- transactional migration — see comment above.
CREATE UNIQUE INDEX users_username_lower_per_tenant ON users (tenant_id, lower(username));
