-- Admin Console OAuth Client — replaces the legacy kotauth-admin-console confidential
-- client with a public PKCE client. Also provisions an admin role on the master tenant
-- and assigns it to the default admin user. Sets the master tenant display name for
-- a clear login page experience.

-- Step 0: Configure master tenant for admin console use
-- - Display name for the OAuth login page
-- - Disable public registration (security: only admins should exist on master)
UPDATE tenants SET
    display_name = 'KotAuth Admin',
    registration_enabled = false
WHERE slug = 'master';

-- Master tenant theme: sharp corners (0px radius) matching the admin console aesthetic
UPDATE workspace_theme SET
    border_radius = '0px'
WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'master');

-- Step 1: Replace legacy client (update in place to preserve the row)
UPDATE clients SET
    client_id = 'kotauth-admin',
    name = 'KotAuth Admin Console',
    access_type = 'public',
    description = 'Built-in OAuth client for the admin console — Authorization Code + PKCE',
    enabled = true
WHERE client_id = 'kotauth-admin-console'
  AND tenant_id = (SELECT id FROM tenants WHERE slug = 'master');

-- Step 2: If the legacy client didn't exist (fresh install), insert the new one
INSERT INTO clients (tenant_id, client_id, name, access_type, description, enabled)
SELECT t.id, 'kotauth-admin', 'KotAuth Admin Console', 'public',
       'Built-in OAuth client for the admin console — Authorization Code + PKCE', true
FROM tenants t
WHERE t.slug = 'master'
  AND NOT EXISTS (SELECT 1 FROM clients WHERE client_id = 'kotauth-admin' AND tenant_id = t.id);

-- Step 3: Create admin role on master tenant (for console access gating)
INSERT INTO roles (tenant_id, name, description, scope)
SELECT t.id, 'admin', 'Full administrative access to the KotAuth admin console', 'tenant'
FROM tenants t
WHERE t.slug = 'master'
  AND NOT EXISTS (
    SELECT 1 FROM roles WHERE tenant_id = t.id AND name = 'admin' AND scope = 'tenant'
  );

-- Step 4: Assign admin role to the default admin user
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN tenants t ON u.tenant_id = t.id
JOIN roles r ON r.tenant_id = t.id AND r.name = 'admin' AND r.scope = 'tenant'
WHERE t.slug = 'master' AND u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM user_roles WHERE user_id = u.id AND role_id = r.id);
