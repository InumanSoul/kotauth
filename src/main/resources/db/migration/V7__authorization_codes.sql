-- V7: Authorization codes for OAuth2 Authorization Code Flow + PKCE
--
-- Short-lived (5 minutes) single-use codes issued by the authorization endpoint.
-- Exchanged at the token endpoint for an access + refresh token pair.
--
-- PKCE columns (code_challenge, code_challenge_method):
--   - Required for public clients (PKCE policy: Option A from ADR).
--   - Optional for confidential clients.
--   - code_challenge_method is always 'S256' (plain is not accepted).
--
-- Codes are marked as used (used_at) rather than deleted to prevent replay
-- attacks and to provide an audit trail. A cleanup job should purge expired
-- codes older than 24 hours periodically.

CREATE TABLE authorization_codes (
    id                      SERIAL       PRIMARY KEY,
    code                    VARCHAR(128) NOT NULL UNIQUE,
    tenant_id               INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    client_id               INTEGER      NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    user_id                 INTEGER      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    redirect_uri            TEXT         NOT NULL,
    scopes                  TEXT         NOT NULL DEFAULT 'openid',
    code_challenge          VARCHAR(512),
    code_challenge_method   VARCHAR(10),
    nonce                   VARCHAR(512),
    state                   VARCHAR(512),
    expires_at              TIMESTAMPTZ  NOT NULL,
    used_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_codes_code      ON authorization_codes(code);
CREATE INDEX idx_auth_codes_tenant_id ON authorization_codes(tenant_id);
CREATE INDEX idx_auth_codes_expires   ON authorization_codes(expires_at) WHERE used_at IS NULL;
