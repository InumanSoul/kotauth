-- V10: Email verification tokens — Phase 3b
--
-- Time-limited tokens sent to a user's email address to verify ownership.
-- The token_hash column stores SHA-256(rawToken) — the raw token lives only in the email.
-- Tokens are valid for 24 hours. used_at is set on successful verification (never deleted,
-- for audit purposes). Previous unused tokens for the same user are deleted before issuing
-- a new one (prevents token accumulation).
--
-- On DELETE CASCADE: if the user or tenant is deleted, their tokens are cleaned up.

CREATE TABLE email_verification_tokens (
    id          SERIAL       PRIMARY KEY,
    user_id     INTEGER      NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    tenant_id   INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_evtoken_user_id ON email_verification_tokens(user_id);
CREATE INDEX idx_evtoken_expires ON email_verification_tokens(expires_at);
