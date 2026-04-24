-- V34: Tenant-level rules that project user attributes into issued JWTs.
-- (tenant_id, attribute_key) is the natural PK — one mapping per attribute per tenant.
-- (tenant_id, claim_name) is UNIQUE — two attributes cannot collide on the same claim name.

CREATE TABLE tenant_claim_mappers (
    tenant_id         INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    attribute_key     VARCHAR(64)  NOT NULL,
    claim_name        VARCHAR(128) NOT NULL,
    include_in_access BOOLEAN      NOT NULL DEFAULT TRUE,
    include_in_id     BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, attribute_key)
);

-- Enforces no two attributes in the same tenant projecting to the same claim name.
CREATE UNIQUE INDEX idx_claim_mappers_claim_name ON tenant_claim_mappers (tenant_id, claim_name);
