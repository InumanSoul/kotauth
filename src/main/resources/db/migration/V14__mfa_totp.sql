-- =============================================================================
-- V14: MFA / TOTP support (Phase 3c)
--
-- Adds:
--   1. mfa_enrollments — tracks TOTP enrollment per user
--   2. mfa_recovery_codes — one-time backup codes
--   3. Tenant-level MFA policy columns
-- =============================================================================

-- -----------------------------------------------------------------------------
-- MFA enrollments — one TOTP enrollment per user (expandable to WebAuthn later)
-- -----------------------------------------------------------------------------
CREATE TABLE mfa_enrollments (
    id          SERIAL PRIMARY KEY,
    user_id     INTEGER      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id   INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    -- 'totp' for now; future: 'webauthn', 'sms'
    method      VARCHAR(20)  NOT NULL DEFAULT 'totp',
    -- Base32-encoded TOTP secret (encrypted at rest via EncryptionService)
    secret      TEXT         NOT NULL,
    -- Whether the user has verified the enrollment (scanned QR + entered code)
    verified    BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    verified_at TIMESTAMPTZ,

    -- One enrollment per method per user
    CONSTRAINT mfa_enrollment_unique UNIQUE (user_id, method)
);

CREATE INDEX idx_mfa_enrollments_user ON mfa_enrollments (user_id);

-- -----------------------------------------------------------------------------
-- MFA recovery codes — one-time use backup codes
-- Generated alongside TOTP enrollment. Each code is bcrypt-hashed.
-- -----------------------------------------------------------------------------
CREATE TABLE mfa_recovery_codes (
    id            SERIAL PRIMARY KEY,
    user_id       INTEGER      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id     INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code_hash     VARCHAR(128) NOT NULL,
    used_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mfa_recovery_codes_user ON mfa_recovery_codes (user_id);

-- -----------------------------------------------------------------------------
-- Tenant-level MFA policy
-- -----------------------------------------------------------------------------
ALTER TABLE tenants
    ADD COLUMN mfa_policy VARCHAR(20) NOT NULL DEFAULT 'optional';
-- Values: 'optional', 'required', 'required_admins'
-- optional       = users can opt-in via self-service portal
-- required       = all users must enroll MFA before first login completes
-- required_admins = only users with admin-level roles must enroll

-- Track whether user has completed MFA enrollment
ALTER TABLE users
    ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
