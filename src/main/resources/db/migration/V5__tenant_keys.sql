-- V5: Per-tenant RSA signing keys (ADR-002: RS256)
--
-- Each tenant (Authorization Server) has its own RSA key pair for signing JWTs.
-- This enables per-tenant key rotation without affecting other tenants.
-- The JWKS endpoint publishes the public keys so clients can verify tokens offline.
--
-- Multiple keys per tenant are supported (enabled=true → in active use).
-- On rotation: insert a new key, allow old tokens to expire, then set old enabled=false.

CREATE TABLE tenant_keys (
    id              SERIAL PRIMARY KEY,
    tenant_id       INTEGER     NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    key_id          VARCHAR(128) NOT NULL,
    algorithm       VARCHAR(10)  NOT NULL DEFAULT 'RS256',
    public_key      TEXT         NOT NULL,
    private_key     TEXT         NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    UNIQUE (tenant_id, key_id)
);

CREATE INDEX idx_tenant_keys_tenant_id ON tenant_keys(tenant_id);
CREATE INDEX idx_tenant_keys_enabled   ON tenant_keys(tenant_id, enabled) WHERE enabled = TRUE;
