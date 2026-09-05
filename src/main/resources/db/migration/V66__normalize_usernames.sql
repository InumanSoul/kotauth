-- V66: Normalize usernames to trimmed, lowercased [a-z0-9._@+-]+ form (v1.24.0 hardening).
--
-- New product rule: usernames are ALWAYS stored normalized — trimmed, lowercased, and
-- matching [a-z0-9._@+-]+. Historically AuthService.register performed no username format
-- validation at all, so rows exist that are mixed case or contain arbitrary characters
-- (e.g. "John Doe"). This migration, in order:
--   1. Aborts if normalizing would collide two rows within the same tenant — an operator
--      must de-duplicate those by hand. Silently merging or dropping identity rows is not
--      an acceptable failure mode.
--   2. Aborts if normalizing a row would produce an empty string or something that still
--      fails the pattern — a username made entirely of characters outside [a-z0-9._@+-]
--      (e.g. a non-Latin script like 'Иван' or '用户') collapses to '' under this migration's
--      own rewrite rule, and '' would otherwise commit silently into a NOT NULL column,
--      leaving a user who can never sign in by username again and no record of why. This
--      matches the same reject-don't-rewrite policy every application write path now
--      follows (see UsernamePolicy) — this is simply the one place that rewrite would be
--      irreversible, so the check has to run before Step 3 instead of after.
--   3. Rewrites every existing username to its normalized form: lowercase; each run of
--      forbidden characters collapsed to a single '.'; leading/trailing '.', '_', '-' stripped.
--   4. Adds a CHECK (username = lower(username)) constraint — the unique index below enforces
--      uniqueness on lower(username), but nothing previously enforced that storage is actually
--      lowercase; that invariant rested entirely on application code with no database backstop.
--   5. Adds a unique index on (tenant_id, lower(username)) to enforce the rule going forward.
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

-- Step 2: refuse to proceed if normalization would produce an empty or still-invalid value.
-- The username itself is deliberately NOT included in the exception message — same reasoning
-- as the collision case above, which names tenant and count rather than the raw values.
DO $$
DECLARE
    offender RECORD;
BEGIN
    SELECT id, tenant_id
    INTO offender
    FROM users
    WHERE pg_temp.kauth_normalize_username(username) = ''
       OR pg_temp.kauth_normalize_username(username) !~ '^[a-z0-9._@+-]+$'
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'V66 aborted: tenant % has a user (id %) whose username normalizes to an empty or '
            'invalid value — likely a username made entirely of characters outside '
            '[a-z0-9._@+-] (e.g. a non-Latin script). Rename this user by hand (on the '
            'pre-upgrade version) to something matching [a-z0-9._@+-]+ before re-running this '
            'migration.',
            offender.tenant_id, offender.id;
    END IF;
END $$;

-- Step 3: normalize every existing username.
UPDATE users
SET username = pg_temp.kauth_normalize_username(username)
WHERE username <> pg_temp.kauth_normalize_username(username);

DROP FUNCTION pg_temp.kauth_normalize_username(text);

-- Step 4: back the "storage is always lowercase" invariant with a database constraint, not just
-- application code. Deliberately not "= normalize(...)" — Postgres has no built-in equivalent of
-- the collapse/strip rule above, and re-deriving it here would duplicate logic that only needs to
-- hold once, at migration time; going forward every write path already normalizes before writing.
ALTER TABLE users ADD CONSTRAINT users_username_lowercase_chk CHECK (username = lower(username));

-- Step 5: enforce the rule going forward. Cannot use CONCURRENTLY inside Flyway's
-- transactional migration — see comment above.
CREATE UNIQUE INDEX users_username_lower_per_tenant ON users (tenant_id, lower(username));
