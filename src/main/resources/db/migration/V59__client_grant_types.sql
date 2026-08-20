CREATE TABLE client_grant_types (
    client_id  INTEGER     NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    grant_type VARCHAR(40) NOT NULL,
    PRIMARY KEY (client_id, grant_type),
    CONSTRAINT chk_client_grant_types_value
        CHECK (grant_type IN ('authorization_code', 'client_credentials', 'refresh_token'))
);

CREATE INDEX idx_client_grant_types_client ON client_grant_types(client_id);

-- Backfill preserves what each access type can do today.
-- confidential: all three. public: authorization_code + refresh_token
-- (client_credentials was already refused to it). bearer_only: none — it
-- validates tokens and initiates no flows.
INSERT INTO client_grant_types (client_id, grant_type)
SELECT id, 'authorization_code' FROM clients WHERE access_type IN ('public', 'confidential');

INSERT INTO client_grant_types (client_id, grant_type)
SELECT id, 'refresh_token' FROM clients WHERE access_type IN ('public', 'confidential');

INSERT INTO client_grant_types (client_id, grant_type)
SELECT id, 'client_credentials' FROM clients WHERE access_type = 'confidential';
