-- V24: Rename bg_card → surface, add font_family to workspace_theme
--
-- bg_card is renamed to 'surface' to better describe its role as the
-- elevated-surface background (cards, sections, sidebar, topbar).
-- font_family allows tenants to choose a Google Fonts typeface.

ALTER TABLE workspace_theme
    RENAME COLUMN bg_card TO surface;

ALTER TABLE workspace_theme
    ADD COLUMN font_family VARCHAR(60) NOT NULL DEFAULT 'Inter';
