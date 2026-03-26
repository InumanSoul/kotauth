-- Composite index for findActiveByUser queries (login session limits, portal session list).
-- The existing idx_sessions_active covers (tenant_id, revoked_at) but cannot use user_id.
CREATE INDEX IF NOT EXISTS idx_sessions_tenant_user_active
    ON sessions(tenant_id, user_id, expires_at)
    WHERE revoked_at IS NULL AND user_id IS NOT NULL;

-- Composite index for audit log queries ordered by created_at within a tenant.
-- The existing separate indexes on tenant_id and created_at cannot be combined for filter + sort.
CREATE INDEX IF NOT EXISTS idx_audit_tenant_created
    ON audit_log(tenant_id, created_at DESC);
