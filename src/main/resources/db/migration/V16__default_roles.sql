-- =============================================================================
-- V16: Seed default tenant-scoped roles for all existing workspaces.
--
-- Every workspace gets two baseline roles on creation:
--   admin  — full administrative access within the workspace
--   user   — standard authenticated principal; default for self-registrations
--
-- These are the smallest meaningful set — universal enough to be useful out
-- of the box, opinionated enough to match 90% of use cases without being
-- presumptuous. Operators can rename, delete, or extend them freely.
--
-- Idempotent: uses INSERT ... ON CONFLICT DO NOTHING so re-runs are safe.
-- New tenants created after this migration get defaults via application logic
-- (AdminService / KeyProvisioningService).
-- =============================================================================

INSERT INTO roles (tenant_id, name, description, scope, created_at)
SELECT
    t.id,
    r.name,
    r.description,
    'tenant',
    NOW()
FROM tenants t
CROSS JOIN (
    VALUES
        ('admin', 'Full administrative access within this workspace'),
        ('user',  'Standard authenticated user — default role for self-registrations')
) AS r(name, description)
ON CONFLICT ON CONSTRAINT roles_name_unique_per_scope DO NOTHING;
