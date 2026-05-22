-- V42: Per-client default roles granted at self-registration.
--
-- When a user self-registers through an OAuth client's /authorize flow, every
-- role associated with that client here is assigned to the new user. Builds on
-- the existing roles / user_roles primitives — no new entitlement concept.
--
-- Cross-client privilege escalation is prevented at the service layer: a
-- client-scoped role belonging to a different client must not be associated
-- as a default for this client. Tenant-scoped roles are valid for any client.
--
-- The (client_id, role_id) primary key doubles as the lookup index for
-- "default roles for client X" — client_id is the leading column.

CREATE TABLE client_default_roles (
    client_id INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    role_id   INTEGER NOT NULL REFERENCES roles(id)   ON DELETE CASCADE,
    PRIMARY KEY (client_id, role_id)
);
