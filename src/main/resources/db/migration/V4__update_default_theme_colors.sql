-- V4: Update default theme colors from legacy purple palette to Zinc-dark + brand cyan
--
-- V3 shipped with a purple accent (#bb86fc) on a custom dark surface.
-- The design system has been updated to Zinc-dark surfaces with a cyan brand accent (#1FBCFF).
--
-- This migration:
--   1. Updates the column-level DEFAULT values (affects new rows going forward)
--   2. Backfills existing rows that still carry the V3 defaults (typically just 'master')
--      leaving any row that was deliberately customized untouched.

-- 1. Update column defaults
ALTER TABLE tenants
    ALTER COLUMN theme_accent_color  SET DEFAULT '#1FBCFF',
    ALTER COLUMN theme_accent_hover  SET DEFAULT '#0ea5d9',
    ALTER COLUMN theme_bg_deep       SET DEFAULT '#09090b',
    ALTER COLUMN theme_bg_card       SET DEFAULT '#18181b',
    ALTER COLUMN theme_bg_input      SET DEFAULT '#27272a',
    ALTER COLUMN theme_border_color  SET DEFAULT '#3f3f46',
    ALTER COLUMN theme_text_primary  SET DEFAULT '#fafafa',
    ALTER COLUMN theme_text_muted    SET DEFAULT '#a1a1aa';

-- 2. Backfill rows that still hold the exact V3 legacy defaults
--    (rows with custom colors are left untouched)
UPDATE tenants
SET
    theme_accent_color = '#1FBCFF',
    theme_accent_hover = '#0ea5d9',
    theme_bg_deep      = '#09090b',
    theme_bg_card      = '#18181b',
    theme_bg_input     = '#27272a',
    theme_border_color = '#3f3f46',
    theme_text_primary = '#fafafa',
    theme_text_muted   = '#a1a1aa'
WHERE
    theme_accent_color = '#bb86fc'
    AND theme_accent_hover = '#9965f4'
    AND theme_bg_deep      = '#0f0f13'
    AND theme_bg_card      = '#1a1a24'
    AND theme_bg_input     = '#252532'
    AND theme_border_color = '#2e2e3e'
    AND theme_text_primary = '#e8e8f0'
    AND theme_text_muted   = '#6b6b80';
