-- V39: Per-tenant "Require passwordless sign-in" toggle.
-- Free-rider: promote magic-link TTL into per-tenant config while the table is open.

ALTER TABLE tenant_security_config
    ADD COLUMN password_login_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN magic_link_token_ttl_minutes INTEGER NOT NULL DEFAULT 15;
