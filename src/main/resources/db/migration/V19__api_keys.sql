-- =============================================================================
-- V19: API Keys — machine-to-machine REST API authentication (Phase 3a)
--
-- Design:
--   • Plaintext key is returned ONCE on creation and never stored.
--   • Only a SHA-256 hash is persisted (same pattern as client secrets).
--   • Key format: kauth_<tenantSlug>_<32-random-bytes-base64url>
--   • key_prefix stores the first 8 chars of the raw key for display purposes.
--   • Scopes are stored as a comma-separated string for simplicity.
-- =============================================================================

CREATE TABLE api_keys (
    id              SERIAL PRIMARY KEY,
    tenant_id       INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    -- Human-readable label (e.g. "CI/CD pipeline", "Terraform provider")
    name            VARCHAR(128) NOT NULL,
    -- First 8 chars of the raw key for display (e.g. "kauth_my")
    key_prefix      VARCHAR(16)  NOT NULL,
    -- SHA-256 hex digest of the full raw key
    key_hash        VARCHAR(64)  NOT NULL UNIQUE,
    -- Comma-separated permission scopes: users:read, users:write, roles:read, etc.
    scopes          TEXT         NOT NULL DEFAULT '',
    -- NULL = never expires
    expires_at      TIMESTAMPTZ,
    -- Informational — updated on every successful auth (best-effort, no transaction)
    last_used_at    TIMESTAMPTZ,
    -- Soft revocation — disabled keys are rejected immediately
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_api_keys_tenant   ON api_keys (tenant_id);
CREATE INDEX idx_api_keys_hash     ON api_keys (key_hash);
CREATE INDEX idx_api_keys_enabled  ON api_keys (tenant_id, enabled);
