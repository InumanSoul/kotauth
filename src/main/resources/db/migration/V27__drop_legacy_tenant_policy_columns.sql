-- Drops the legacy security policy columns from the tenants table.
-- These were migrated to tenant_security_config in V26.
-- The columns have been dead (unused by application code) since the SecurityConfig extraction.

ALTER TABLE tenants
    DROP COLUMN IF EXISTS password_policy_min_length,
    DROP COLUMN IF EXISTS password_policy_require_special,
    DROP COLUMN IF EXISTS password_policy_require_uppercase,
    DROP COLUMN IF EXISTS password_policy_require_number,
    DROP COLUMN IF EXISTS password_policy_history_count,
    DROP COLUMN IF EXISTS password_policy_max_age_days,
    DROP COLUMN IF EXISTS password_policy_blacklist_enabled,
    DROP COLUMN IF EXISTS mfa_policy;
