-- V74: Users approve what an application may access before it receives a code.
--
-- clients.consent_required is off for every application that exists today, so an upgrade never
-- starts prompting users of a live app. Applications created from now on ask by default; the
-- service layer sets that, not this column default.
--
-- user_consents holds what a user allowed an application, one row per user and application.
-- Scopes accumulate: a later request for more scopes adds to the row instead of replacing it.
--
-- resource_servers.scope_descriptions maps a declared scope to the text the consent screen shows.

ALTER TABLE clients ADD COLUMN consent_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE resource_servers ADD COLUMN scope_descriptions JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE user_consents (
    id          SERIAL      PRIMARY KEY,
    tenant_id   INTEGER     NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id     INTEGER     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id   INTEGER     NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    scopes      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_consents_user_client UNIQUE (user_id, client_id)
);

CREATE INDEX idx_user_consents_tenant_user ON user_consents(tenant_id, user_id);
