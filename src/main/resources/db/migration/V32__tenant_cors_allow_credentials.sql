ALTER TABLE tenant_security_config
    ADD COLUMN cors_allow_credentials BOOLEAN NOT NULL DEFAULT FALSE;
