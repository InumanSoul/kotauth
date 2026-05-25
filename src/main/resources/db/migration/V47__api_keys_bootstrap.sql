-- V47: Bootstrap-friendly API keys (v1.12.0).
-- (tenant_id, name) UNIQUE enables idempotent upsert by name from
-- KAUTH_BOOTSTRAP_API_KEYS — rotating a key = edit the env var. bootstrap_name
-- marks env-provisioned rows so the admin UI can show provenance.

ALTER TABLE api_keys
    ADD CONSTRAINT uq_api_keys_tenant_name UNIQUE (tenant_id, name),
    ADD COLUMN bootstrap_name VARCHAR(128);

CREATE INDEX idx_api_keys_bootstrap ON api_keys (bootstrap_name)
    WHERE bootstrap_name IS NOT NULL;
