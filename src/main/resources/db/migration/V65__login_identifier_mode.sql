-- V65: Per-tenant sign-in identifier mode (v1.24.0).
-- USERNAME preserves pre-1.24 behaviour for every existing workspace.

ALTER TABLE tenant_security_config
    ADD COLUMN login_identifier_mode VARCHAR(10) NOT NULL DEFAULT 'USERNAME'
    CHECK (login_identifier_mode IN ('USERNAME', 'EMAIL', 'EITHER'));
