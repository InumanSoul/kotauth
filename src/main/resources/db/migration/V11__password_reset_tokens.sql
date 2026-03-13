-- V11: Password reset tokens — Phase 3b
--
-- Time-limited tokens sent via email for password reset flows.
-- Token expiry is intentionally short (1 hour) given the sensitivity of the operation.
-- The token_hash column stores SHA-256(rawToken) — the raw token is never persisted.
-- ip_address records who initiated the reset request (audit trail).
-- On successful use: used_at is set AND all sessions for that user are revoked.
-- All existing unused tokens for a user are deleted before issuing a new one.
--
-- On DELETE CASCADE: token cleanup follows the user or tenant.

CREATE TABLE password_reset_tokens (
    id          SERIAL       PRIMARY KEY,
    user_id     INTEGER      NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    tenant_id   INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prtoken_user_id ON password_reset_tokens(user_id);
CREATE INDEX idx_prtoken_expires ON password_reset_tokens(expires_at);
