-- =============================================================================
-- V17: Identity Providers — Social Login (Phase 2)
--
-- Adds:
--   1. identity_providers — per-tenant OAuth2 provider configuration
--      (Google, GitHub; extensible to Microsoft, Apple, etc.)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- identity_providers — stores OAuth2 client credentials per provider per tenant
-- The client_secret is stored AES-256-GCM encrypted (EncryptionService).
-- -----------------------------------------------------------------------------
CREATE TABLE identity_providers (
    id            SERIAL PRIMARY KEY,
    tenant_id     INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    -- Provider identifier: 'google', 'github'
    provider      VARCHAR(32)  NOT NULL,
    -- OAuth2 client credentials (client_secret is encrypted at rest)
    client_id     VARCHAR(255) NOT NULL,
    client_secret TEXT         NOT NULL,
    -- Whether this provider is currently active for the tenant
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- One config per provider per tenant
    CONSTRAINT idp_unique_provider_tenant UNIQUE (tenant_id, provider)
);

CREATE INDEX idx_identity_providers_tenant ON identity_providers (tenant_id);
