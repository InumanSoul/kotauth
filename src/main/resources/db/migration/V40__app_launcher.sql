-- V40: Per-application launcher configuration.
-- Drives the portal app launcher at /t/{slug}/launcher.
ALTER TABLE clients
    ADD COLUMN launcher_url           VARCHAR(500),
    ADD COLUMN icon_url               VARCHAR(500),
    ADD COLUMN launcher_visible       BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN launcher_display_order INTEGER NOT NULL DEFAULT 0;
