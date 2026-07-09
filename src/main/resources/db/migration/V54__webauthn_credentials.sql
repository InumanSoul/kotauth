ALTER TABLE tenants ADD COLUMN password_login_disabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE webauthn_credentials (
    id                BIGSERIAL PRIMARY KEY,
    user_id           INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id         INTEGER NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    credential_id     TEXT NOT NULL UNIQUE,
    public_key_cose   BYTEA NOT NULL,
    sign_counter      BIGINT NOT NULL DEFAULT 0,
    aaguid            VARCHAR(36),
    transports        JSONB NOT NULL DEFAULT '[]',
    name              VARCHAR(64) NOT NULL,
    backup_eligible   BOOLEAN NOT NULL DEFAULT FALSE,
    backup_state      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL,
    last_used_at      TIMESTAMPTZ
);

CREATE INDEX idx_webauthn_credentials_user ON webauthn_credentials(user_id, tenant_id);

ALTER TABLE tenants ADD COLUMN passkeys_enabled BOOLEAN NOT NULL DEFAULT TRUE;
