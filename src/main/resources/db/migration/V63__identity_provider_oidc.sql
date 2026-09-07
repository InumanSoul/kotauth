-- =============================================================================
-- V63: Identity Providers — generic OIDC configuration
--
-- Additive only: every existing row becomes a valid 'oauth2' provider with JIT off,
-- so no backfill is needed and no existing tenant changes behaviour. The JIT columns
-- land here even though a later phase reads them — this is the phase's only migration.
-- =============================================================================

ALTER TABLE identity_providers
    ADD COLUMN kind                   VARCHAR(16)  NOT NULL DEFAULT 'oauth2',
    ADD COLUMN display_name           VARCHAR(64),
    ADD COLUMN issuer                 VARCHAR(255),
    ADD COLUMN authorization_endpoint VARCHAR(512),
    ADD COLUMN token_endpoint         VARCHAR(512),
    ADD COLUMN jwks_uri               VARCHAR(512),
    ADD COLUMN scopes                 VARCHAR(255) NOT NULL DEFAULT 'openid email profile',
    ADD COLUMN jit_enabled            BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Comma-separated; normalised on write so a reader never has to.
    ADD COLUMN jit_allowed_domains    TEXT;
