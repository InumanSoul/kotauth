-- Extract theme columns from tenants into a dedicated workspace_theme table.
-- This separates UI customization from core tenant configuration.
--
-- Strategy:
--   1. Create workspace_theme table with all theme fields
--   2. Migrate existing data from tenants into workspace_theme
--   3. Add accent_foreground column for button text contrast control
--   4. Drop the theme columns from tenants

-- 1. Create the new table
CREATE TABLE workspace_theme (
    id                  SERIAL PRIMARY KEY,
    tenant_id           INTEGER NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    accent_color        VARCHAR(30)  NOT NULL DEFAULT '#1FBCFF',
    accent_hover        VARCHAR(30)  NOT NULL DEFAULT '#0ea5d9',
    accent_foreground   VARCHAR(30)  NOT NULL DEFAULT '#05080a',
    bg_deep             VARCHAR(30)  NOT NULL DEFAULT '#09090b',
    bg_card             VARCHAR(30)  NOT NULL DEFAULT '#18181b',
    bg_input            VARCHAR(30)  NOT NULL DEFAULT '#27272a',
    border_color        VARCHAR(30)  NOT NULL DEFAULT '#3f3f46',
    border_radius       VARCHAR(20)  NOT NULL DEFAULT '8px',
    text_primary        VARCHAR(30)  NOT NULL DEFAULT '#fafafa',
    text_muted          VARCHAR(30)  NOT NULL DEFAULT '#a1a1aa',
    logo_url            VARCHAR(500),
    favicon_url         VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_workspace_theme_tenant ON workspace_theme(tenant_id);

-- 2. Migrate existing theme data from tenants
INSERT INTO workspace_theme (
    tenant_id, accent_color, accent_hover, bg_deep, bg_card, bg_input,
    border_color, border_radius, text_primary, text_muted, logo_url, favicon_url
)
SELECT
    id, theme_accent_color, theme_accent_hover, theme_bg_deep, theme_bg_card,
    theme_bg_input, theme_border_color, theme_border_radius, theme_text_primary,
    theme_text_muted, theme_logo_url, theme_favicon_url
FROM tenants;

-- 3. Drop the old columns from tenants
ALTER TABLE tenants
    DROP COLUMN theme_accent_color,
    DROP COLUMN theme_accent_hover,
    DROP COLUMN theme_bg_deep,
    DROP COLUMN theme_bg_card,
    DROP COLUMN theme_bg_input,
    DROP COLUMN theme_border_color,
    DROP COLUMN theme_border_radius,
    DROP COLUMN theme_text_primary,
    DROP COLUMN theme_text_muted,
    DROP COLUMN theme_logo_url,
    DROP COLUMN theme_favicon_url;
