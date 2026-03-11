-- =============================================================================
-- V2: OAuth 2.0 clients
--
-- A Client is an application registered within a tenant.
-- It maps directly to an OAuth 2.0 client_id + client_secret pair.
--
-- access_type drives the security model:
--   public       — SPAs, mobile apps. No secret. Requires PKCE.
--   confidential — Backend services. Has a secret stored as a BCrypt hash.
--   bearer_only  — Resource servers that only validate tokens, never initiate login.
-- =============================================================================

CREATE TYPE client_access_type AS ENUM ('public', 'confidential', 'bearer_only');

CREATE TABLE clients (
    id                    SERIAL PRIMARY KEY,
    tenant_id             INTEGER             NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    client_id             VARCHAR(100)        NOT NULL,
    client_secret_hash    VARCHAR(128),                 -- NULL for public clients
    name                  VARCHAR(100)        NOT NULL,
    description           TEXT,
    access_type           client_access_type  NOT NULL DEFAULT 'public',
    token_expiry_override INTEGER,                      -- NULL = inherit from tenant
    enabled               BOOLEAN             NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    -- client_id must be unique within a tenant (not globally)
    CONSTRAINT clients_client_id_per_tenant UNIQUE (tenant_id, client_id)
);

-- Allowed redirect URIs — 1:many per client.
-- Validated on every authorization code request to prevent open redirect attacks.
CREATE TABLE client_redirect_uris (
    id        SERIAL  PRIMARY KEY,
    client_id INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    uri       VARCHAR(500) NOT NULL
);

CREATE INDEX idx_clients_tenant ON clients (tenant_id);

-- Seed a default admin client in the master tenant.
-- This client will be used by the KotAuth admin console itself.
INSERT INTO clients (tenant_id, client_id, name, access_type, description)
SELECT
    t.id,
    'kotauth-admin-console',
    'KotAuth Admin Console',
    'confidential',
    'Built-in client for the KotAuth administration UI'
FROM tenants t
WHERE t.slug = 'master';
