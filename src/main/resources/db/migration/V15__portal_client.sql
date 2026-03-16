-- =============================================================================
-- V15: Built-in portal client — one per tenant
--
-- Each tenant gets a pre-provisioned 'kotauth-portal' PUBLIC client so the
-- self-service portal can authenticate users through the standard OAuth
-- Authorization Code + PKCE flow instead of its own parallel login path.
--
-- Access type: PUBLIC (no client_secret, PKCE required).
-- The client is disabled for the master tenant — the portal is for end-user
-- self-service and the master tenant only houses platform administrators who
-- use the admin console directly.
--
-- Redirect URIs are registered at application startup by PortalClientProvisioning
-- once the KAUTH_BASE_URL is known. The clients row is created here; the
-- redirect URI row is upserted at runtime so it always reflects the live base URL.
-- =============================================================================

INSERT INTO clients (tenant_id, client_id, name, access_type, description, enabled)
SELECT
    t.id,
    'kotauth-portal',
    'KotAuth Self-Service Portal',
    'public',
    'Built-in client for the tenant self-service portal (profile, password, MFA)',
    -- Disabled for the master tenant; master users use the admin console, not the portal
    (t.slug != 'master')
FROM tenants t
-- Skip if already exists (idempotent re-runs)
WHERE NOT EXISTS (
    SELECT 1 FROM clients c
    WHERE c.tenant_id = t.id AND c.client_id = 'kotauth-portal'
);
