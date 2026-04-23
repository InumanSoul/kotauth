-- V33: Per-user key/value metadata.
-- Values are always string-encoded — callers serialize structured data themselves.
-- Projected into JWT claims via tenant_claim_mappers (V34).

CREATE TABLE user_attributes (
    user_id    INTEGER     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id  INTEGER     NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    key        VARCHAR(64) NOT NULL,
    value      TEXT        NOT NULL CHECK (char_length(value) <= 1024),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);

-- Covers the hot-path query: "all attributes for user X in tenant Y".
CREATE INDEX idx_user_attributes_tenant_user ON user_attributes (tenant_id, user_id);
