-- V6: User sessions — persisted token state
--
-- Every issued token set creates a session record.
-- The access_token_hash and refresh_token_hash are SHA-256 hashes of the
-- raw token strings — never store tokens in plain text.
--
-- Revocation: set revoked_at to a non-null timestamp.
-- Refresh token rotation: on every refresh, insert a new session row and
-- mark the old one as revoked.
--
-- Sessions are scoped by tenant_id for easy per-tenant cleanup and audit.

CREATE TABLE sessions (
    id                   SERIAL      PRIMARY KEY,
    tenant_id            INTEGER     NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id              INTEGER     REFERENCES users(id) ON DELETE CASCADE,   -- NULL for client_credentials
    client_id            INTEGER     REFERENCES clients(id) ON DELETE SET NULL,
    access_token_hash    VARCHAR(64) NOT NULL UNIQUE,
    refresh_token_hash   VARCHAR(64) UNIQUE,
    scopes               TEXT        NOT NULL DEFAULT 'openid',
    ip_address           VARCHAR(45),
    user_agent           TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ NOT NULL,
    refresh_expires_at   TIMESTAMPTZ,
    last_activity_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at           TIMESTAMPTZ
);

CREATE INDEX idx_sessions_access_token_hash  ON sessions(access_token_hash);
CREATE INDEX idx_sessions_refresh_token_hash ON sessions(refresh_token_hash) WHERE refresh_token_hash IS NOT NULL;
CREATE INDEX idx_sessions_user_id            ON sessions(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_sessions_tenant_id          ON sessions(tenant_id);
CREATE INDEX idx_sessions_active             ON sessions(tenant_id, revoked_at) WHERE revoked_at IS NULL;
