-- V31: Add active flag to distinguish the signing key from verification-only keys.
-- 'active' = this key signs new tokens (exactly one per tenant).
-- 'enabled' = this key appears in JWKS (used to verify existing tokens).

ALTER TABLE tenant_keys ADD COLUMN active BOOLEAN NOT NULL DEFAULT FALSE;

-- Back-fill: mark the most recently created enabled key as active per tenant.
UPDATE tenant_keys tk
SET active = TRUE
WHERE tk.id = (
    SELECT id FROM tenant_keys
    WHERE tenant_id = tk.tenant_id AND enabled = TRUE
    ORDER BY created_at DESC
    LIMIT 1
);

-- Enforce at most one active key per tenant.
CREATE UNIQUE INDEX idx_tenant_keys_one_active_per_tenant
    ON tenant_keys(tenant_id)
    WHERE active = TRUE;
