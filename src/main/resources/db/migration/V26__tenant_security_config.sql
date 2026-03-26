-- Extracts security policy configuration from the tenants table into a dedicated
-- 1:1 table, following the workspace_themes and workspace_portal_config pattern.
-- Adds account lockout configuration (new) alongside existing password + MFA policy.

CREATE TABLE tenant_security_config (
    id                          SERIAL PRIMARY KEY,
    tenant_id                   INTEGER NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    password_min_length         INTEGER NOT NULL DEFAULT 8,
    password_require_special    BOOLEAN NOT NULL DEFAULT FALSE,
    password_require_uppercase  BOOLEAN NOT NULL DEFAULT FALSE,
    password_require_number     BOOLEAN NOT NULL DEFAULT FALSE,
    password_history_count      INTEGER NOT NULL DEFAULT 0,
    password_max_age_days       INTEGER NOT NULL DEFAULT 0,
    password_blacklist_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    mfa_policy                  VARCHAR(30) NOT NULL DEFAULT 'optional',
    lockout_max_attempts        INTEGER NOT NULL DEFAULT 0,
    lockout_duration_minutes    INTEGER NOT NULL DEFAULT 15,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id)
);

-- Migrate existing security policy data from tenants to the new table
INSERT INTO tenant_security_config (
    tenant_id, password_min_length, password_require_special,
    password_require_uppercase, password_require_number,
    password_history_count, password_max_age_days, password_blacklist_enabled,
    mfa_policy
)
SELECT
    id, password_policy_min_length, password_policy_require_special,
    password_policy_require_uppercase, password_policy_require_number,
    password_policy_history_count, password_policy_max_age_days,
    password_policy_blacklist_enabled, mfa_policy
FROM tenants;

-- User lockout state: failed attempt counter and lock expiry timestamp.
-- These live on the users table because they are mutable per-request state,
-- not tenant-level configuration.
ALTER TABLE users
    ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMPTZ;

-- Partial index for admin "show locked accounts" queries — covers only
-- the small subset of rows that are currently locked.
CREATE INDEX idx_users_locked ON users(tenant_id, locked_until)
    WHERE locked_until IS NOT NULL;
