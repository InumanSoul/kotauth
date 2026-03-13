-- V9: User lifecycle configuration — Phase 3b
--
-- SMTP config stored per-tenant so each workspace can use its own mail server.
-- The smtp_password column stores an AES-256-GCM encrypted value (see EncryptionService).
-- Never store the plaintext password — the application layer handles encrypt/decrypt.
--
-- max_concurrent_sessions: NULL = unlimited. When set, the oldest session is revoked
-- automatically when a new session pushes the user over the limit.
--
-- last_password_change_at: set whenever a user changes their own password or an admin
-- force-resets it. Used to revoke all existing sessions on password change.

ALTER TABLE tenants
    ADD COLUMN smtp_host               VARCHAR(255),
    ADD COLUMN smtp_port               INTEGER     NOT NULL DEFAULT 587,
    ADD COLUMN smtp_username           VARCHAR(255),
    ADD COLUMN smtp_password           TEXT,
    ADD COLUMN smtp_from_address       VARCHAR(255),
    ADD COLUMN smtp_from_name          VARCHAR(255),
    ADD COLUMN smtp_tls_enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN smtp_enabled            BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN max_concurrent_sessions INTEGER;

ALTER TABLE users
    ADD COLUMN last_password_change_at TIMESTAMPTZ;
