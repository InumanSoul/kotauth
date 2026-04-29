-- V37: Per-tenant default UI locale.
-- Null = no tenant preference; resolution falls through to Accept-Language then English.

ALTER TABLE workspace_theme
    ADD COLUMN default_locale VARCHAR(10);
