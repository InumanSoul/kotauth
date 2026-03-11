-- =============================================================================
-- V1: Initial schema — tenants + tenant-scoped users
--
-- Fresh database: Flyway runs this automatically on first boot.
--
-- Existing dev database (was managed by SchemaUtils):
--   Run: docker compose down -v && docker compose up
--   This wipes the volume and starts clean. No production data exists yet.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Tenants — the top-level isolation boundary (one per product / organisation)
-- Each tenant is an independent Authorization Server with its own:
--   users, clients, token settings, password policy, and identity providers.
-- The 'master' tenant is reserved for platform administrators.
-- -----------------------------------------------------------------------------
CREATE TABLE tenants (
    id                              SERIAL PRIMARY KEY,
    slug                            VARCHAR(50)  NOT NULL,
    display_name                    VARCHAR(100) NOT NULL,
    issuer_url                      VARCHAR(255),

    -- Token settings (overridable per client)
    token_expiry_seconds            INTEGER      NOT NULL DEFAULT 3600,
    refresh_token_expiry_seconds    INTEGER      NOT NULL DEFAULT 86400,

    -- Registration policy
    registration_enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    email_verification_required     BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Password policy
    password_policy_min_length      INTEGER      NOT NULL DEFAULT 8,
    password_policy_require_special BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT tenants_slug_unique UNIQUE (slug)
);

-- Seed the master tenant.
-- issuer_url defaults to env-supplied value at runtime; placeholder here.
INSERT INTO tenants (slug, display_name, issuer_url)
VALUES ('master', 'Master', 'https://kauth.example.com');

-- -----------------------------------------------------------------------------
-- Users — scoped to a tenant
--
-- Username and email are unique WITHIN a tenant, not globally.
-- This allows user@company-a.com and user@company-b.com to both exist
-- with username "user" in their respective tenants.
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id              SERIAL PRIMARY KEY,
    tenant_id       INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    username        VARCHAR(50)  NOT NULL,
    email           VARCHAR(255) NOT NULL DEFAULT '',
    password_hash   VARCHAR(128) NOT NULL,
    full_name       VARCHAR(100) NOT NULL,
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_login_at   TIMESTAMPTZ,

    -- Uniqueness is per-tenant, not global
    CONSTRAINT users_username_per_tenant UNIQUE (tenant_id, username),
    CONSTRAINT users_email_per_tenant    UNIQUE (tenant_id, email)
);

-- Index for the most common auth query: find user by tenant + username
CREATE INDEX idx_users_tenant_username ON users (tenant_id, username);
