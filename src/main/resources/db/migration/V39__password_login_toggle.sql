-- V39: Per-tenant "Require passwordless sign-in" toggle.
ALTER TABLE tenant_security_config
    ADD COLUMN password_login_enabled BOOLEAN NOT NULL DEFAULT TRUE;
