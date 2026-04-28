-- V36: Per-tenant toggle for magic-link passwordless authentication.
-- Opt-in: default FALSE preserves existing tenant behavior.

ALTER TABLE tenant_security_config
    ADD COLUMN magic_link_enabled BOOLEAN NOT NULL DEFAULT FALSE;
