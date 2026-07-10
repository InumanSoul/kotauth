-- v1.20.1: consolidate the v1.20.0 tenants.password_login_disabled flag into
-- the pre-existing tenant_security_config.password_login_enabled column
-- (introduced in V39). Any tenant that had password_login_disabled = TRUE
-- gets password_login_enabled = FALSE so behaviour is preserved.
UPDATE tenant_security_config
SET password_login_enabled = FALSE
WHERE tenant_id IN (SELECT id FROM tenants WHERE password_login_disabled = TRUE);

-- Drop the v1.20.0 column now that its meaning lives on the canonical config row.
ALTER TABLE tenants DROP COLUMN password_login_disabled;
