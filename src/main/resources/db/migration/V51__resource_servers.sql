-- V51: RFC 8707 Resource Indicators (v1.18.0).
-- A tenant-scoped registry of APIs that the client_credentials grant can target.
-- The `identifier` is what lands in the JWT `aud` claim; resource servers validate
-- against their own identifier instead of an N×M caller matrix.
--
-- client_authorized_resources gates which clients may request which audiences.
-- The join carries no tenant_id; same-tenant scoping is enforced by the adapter
-- in the write path (a tenant-A client cannot authorize against a tenant-B audience).

CREATE TABLE resource_servers (
    id          SERIAL       PRIMARY KEY,
    tenant_id   INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    identifier  VARCHAR(255) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT resource_servers_identifier_per_tenant UNIQUE (tenant_id, identifier)
);

CREATE INDEX idx_resource_servers_tenant_id ON resource_servers (tenant_id);

CREATE TABLE client_authorized_resources (
    client_id          INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    resource_server_id INTEGER NOT NULL REFERENCES resource_servers(id) ON DELETE CASCADE,
    PRIMARY KEY (client_id, resource_server_id)
);

CREATE INDEX idx_client_authorized_resources_resource_id
    ON client_authorized_resources (resource_server_id);
