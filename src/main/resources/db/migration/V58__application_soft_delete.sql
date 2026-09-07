ALTER TABLE clients
    ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_clients_active
    ON clients(tenant_id)
    WHERE is_deleted = FALSE;
