-- =============================================================================
-- V18: Social Accounts — links provider identities to local users (Phase 2)
--
-- Adds:
--   1. social_accounts — maps (provider, provider_user_id) → local user
-- =============================================================================

-- -----------------------------------------------------------------------------
-- social_accounts — one row per {user, provider} pair.
-- A single local user may have multiple social accounts (e.g. Google + GitHub).
-- A provider user ID can only be linked to one local user per tenant.
-- -----------------------------------------------------------------------------
CREATE TABLE social_accounts (
    id               SERIAL PRIMARY KEY,
    user_id          INTEGER      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id        INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    -- Must match identity_providers.provider values
    provider         VARCHAR(32)  NOT NULL,
    -- The stable unique ID from the provider (Google sub, GitHub id, etc.)
    provider_user_id VARCHAR(255) NOT NULL,
    -- Email returned by the provider at time of linking (informational)
    provider_email   VARCHAR(255),
    -- Display name from the provider
    provider_name    VARCHAR(255),
    -- Avatar URL from the provider (optional)
    avatar_url       TEXT,
    linked_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- A provider identity can only be linked to one local user per tenant
    CONSTRAINT social_account_unique_provider_id UNIQUE (tenant_id, provider, provider_user_id),
    -- A user can have at most one account per provider per tenant
    CONSTRAINT social_account_unique_user_provider UNIQUE (user_id, provider)
);

CREATE INDEX idx_social_accounts_user ON social_accounts (user_id);
CREATE INDEX idx_social_accounts_lookup ON social_accounts (tenant_id, provider, provider_user_id);
