-- Portal layout configuration — separate from tenant core to keep UI concerns isolated.
-- Each tenant can choose a portal layout variant; defaults to SIDEBAR (current behavior).

CREATE TABLE workspace_portal_config (
    id              SERIAL PRIMARY KEY,
    tenant_id       INTEGER NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    layout          VARCHAR(20) NOT NULL DEFAULT 'SIDEBAR',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_portal_config_tenant ON workspace_portal_config(tenant_id);
