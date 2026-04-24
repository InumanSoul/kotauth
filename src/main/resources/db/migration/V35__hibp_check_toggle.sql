-- V35: Per-tenant toggle for HIBP (Have I Been Pwned) breach password check.
-- Opt-in: default FALSE preserves existing tenant behavior when the feature lands.

ALTER TABLE tenant_security_config
    ADD COLUMN hibp_check_enabled BOOLEAN NOT NULL DEFAULT FALSE;
