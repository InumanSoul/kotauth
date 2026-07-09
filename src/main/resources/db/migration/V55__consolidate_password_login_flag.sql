-- Any tenant that set the v1.20.0 flag: force the legacy flag off too.
UPDATE tenants
SET security_config = jsonb_set(security_config, '{passwordLoginEnabled}', 'false')
WHERE password_login_disabled = TRUE;

-- Drop the v1.20.0 column.
ALTER TABLE tenants DROP COLUMN password_login_disabled;
