-- V3: Add per-tenant visual theme columns to tenants
--
-- Strategy: nullable columns with NOT NULL defaults matching KotAuth's
-- built-in dark theme, so existing rows (including 'master') transparently
-- inherit the default theme without a data backfill step.
--
-- Only brand/surface colors are tenant-customizable. Functional colors
-- (error, success, warning) are fixed in the base CSS file — tenants
-- cannot accidentally make error messages invisible.

ALTER TABLE tenants
    ADD COLUMN theme_accent_color      VARCHAR(30)  NOT NULL DEFAULT '#bb86fc',
    ADD COLUMN theme_accent_hover      VARCHAR(30)  NOT NULL DEFAULT '#9965f4',
    ADD COLUMN theme_bg_deep           VARCHAR(30)  NOT NULL DEFAULT '#0f0f13',
    ADD COLUMN theme_bg_card           VARCHAR(30)  NOT NULL DEFAULT '#1a1a24',
    ADD COLUMN theme_bg_input          VARCHAR(30)  NOT NULL DEFAULT '#252532',
    ADD COLUMN theme_border_color      VARCHAR(30)  NOT NULL DEFAULT '#2e2e3e',
    ADD COLUMN theme_border_radius     VARCHAR(20)  NOT NULL DEFAULT '8px',
    ADD COLUMN theme_text_primary      VARCHAR(30)  NOT NULL DEFAULT '#e8e8f0',
    ADD COLUMN theme_text_muted        VARCHAR(30)  NOT NULL DEFAULT '#6b6b80',
    ADD COLUMN theme_logo_url          VARCHAR(500),
    ADD COLUMN theme_favicon_url       VARCHAR(500);
