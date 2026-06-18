-- V51: RFC 8707 Resource Indicators (v1.18.0).

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
