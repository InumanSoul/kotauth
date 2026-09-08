-- V67: Per-client scope allowlists (v1.25.0).
--
-- Until now, authorizing a client against a resource server granted it EVERY scope that
-- resource server declares. `OAuthService.narrowScopes` intersected the request against
-- `resource_servers.scopes` — what the API offers — with nothing client-specific in the
-- intersection. A client created for one capability could mint a token carrying all of them.
--
-- This table is the missing half: which of a resource server's scopes a particular client
-- may actually request. It hangs off `client_authorized_resources` rather than `clients`,
-- because the grant is per (client, resource server) pair — the same shape as V51's
-- authorization table and V59's grant types.

CREATE TABLE client_authorized_scopes (
    client_id          INTEGER      NOT NULL,
    resource_server_id INTEGER      NOT NULL,
    scope              VARCHAR(255) NOT NULL,
    PRIMARY KEY (client_id, resource_server_id, scope),
    -- Composite FK, not two separate ones: a scope grant cannot outlive the authorization
    -- it qualifies, and un-authorizing a resource must take its scope rows with it.
    CONSTRAINT fk_client_authorized_scopes_authorization
        FOREIGN KEY (client_id, resource_server_id)
        REFERENCES client_authorized_resources (client_id, resource_server_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_client_authorized_scopes_client ON client_authorized_scopes (client_id);

-- Backfill reproduces today's behaviour exactly: every existing authorization gets one row
-- per scope its resource server currently declares. No client gains a scope it could not
-- already request, and none loses one. Narrowing only begins when an operator edits a
-- client's scopes, or authorizes a new resource.
--
-- `jsonb_array_elements_text` expands `resource_servers.scopes` (added as JSONB in V53).
-- The `jsonb_typeof` guard skips any row whose column is not an array, so a malformed
-- value cannot abort the upgrade.
INSERT INTO client_authorized_scopes (client_id, resource_server_id, scope)
SELECT car.client_id,
       car.resource_server_id,
       scope_value
FROM client_authorized_resources car
JOIN resource_servers rs ON rs.id = car.resource_server_id
CROSS JOIN LATERAL jsonb_array_elements_text(rs.scopes) AS scope_value
WHERE jsonb_typeof(rs.scopes) = 'array'
ON CONFLICT DO NOTHING;
