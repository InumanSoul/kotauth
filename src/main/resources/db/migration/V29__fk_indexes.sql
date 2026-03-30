-- Add missing indexes on foreign key columns.
-- PostgreSQL does not auto-index FK columns; without these, DELETE CASCADE
-- and filtered queries hit sequential scans.

-- client_redirect_uris: OAuth redirect URI lookups + client deletion cascade
CREATE INDEX IF NOT EXISTS idx_client_redirect_uris_client_id
    ON client_redirect_uris(client_id);

-- sessions: client deletion SET NULL cascade + admin "sessions by client" queries
CREATE INDEX IF NOT EXISTS idx_sessions_client_id
    ON sessions(client_id) WHERE client_id IS NOT NULL;

-- authorization_codes: user deletion cascade + code invalidation on password change
CREATE INDEX IF NOT EXISTS idx_auth_codes_user_id
    ON authorization_codes(user_id);

-- authorization_codes: client deletion cascade
CREATE INDEX IF NOT EXISTS idx_auth_codes_client_id
    ON authorization_codes(client_id);

-- audit_log: largest table long-term; client deletion SET NULL cascade
CREATE INDEX IF NOT EXISTS idx_audit_client_id
    ON audit_log(client_id) WHERE client_id IS NOT NULL;

-- composite_role_mappings: role deletion cascade + inheritance graph traversal
CREATE INDEX IF NOT EXISTS idx_composite_role_child
    ON composite_role_mappings(child_role_id);

-- group_roles: role deletion cascade + "which groups have role X?" queries
CREATE INDEX IF NOT EXISTS idx_group_roles_role_id
    ON group_roles(role_id);

-- Tenant-scoped tables: tenant deletion cascade (small tables, consistency pass)
CREATE INDEX IF NOT EXISTS idx_evtoken_tenant_id
    ON email_verification_tokens(tenant_id);

CREATE INDEX IF NOT EXISTS idx_prtoken_tenant_id
    ON password_reset_tokens(tenant_id);

CREATE INDEX IF NOT EXISTS idx_password_history_tenant_id
    ON password_history(tenant_id);

CREATE INDEX IF NOT EXISTS idx_mfa_enrollments_tenant_id
    ON mfa_enrollments(tenant_id);

CREATE INDEX IF NOT EXISTS idx_mfa_recovery_codes_tenant_id
    ON mfa_recovery_codes(tenant_id);
