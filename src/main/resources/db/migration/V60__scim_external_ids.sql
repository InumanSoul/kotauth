ALTER TABLE users
    ADD COLUMN external_id  VARCHAR(255),
    ADD COLUMN given_name   VARCHAR(255),
    ADD COLUMN family_name  VARCHAR(255);

ALTER TABLE groups
    ADD COLUMN external_id VARCHAR(255);

-- The correlation key must be unique per tenant: a duplicate means two local
-- accounts silently claiming the same identity-provider identity. Partial,
-- because only provisioned rows carry one and NULLs must stay unconstrained.
CREATE UNIQUE INDEX idx_users_external_id
    ON users(tenant_id, external_id) WHERE external_id IS NOT NULL;

CREATE UNIQUE INDEX idx_groups_external_id
    ON groups(tenant_id, external_id) WHERE external_id IS NOT NULL;
