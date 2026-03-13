-- =============================================================================
-- V12: Roles, groups, and their assignment tables (Phase 3c)
--
-- Roles:
--   - Tenant-scoped roles (global to the workspace)
--   - Client-scoped roles (specific to an application)
--   - Composite roles (a role that includes other roles)
--
-- Groups:
--   - Hierarchical user groups with role inheritance
--   - Group-level attributes (JSONB)
--
-- Token claims follow Keycloak convention:
--   realm_access: { roles: ["admin", "user"] }
--   resource_access: { "my-client": { roles: ["editor"] } }
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Roles — tenant or client scoped
-- -----------------------------------------------------------------------------
CREATE TABLE roles (
    id          SERIAL PRIMARY KEY,
    tenant_id   INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    -- 'tenant' = global to the workspace, 'client' = scoped to a specific application
    scope       VARCHAR(10)  NOT NULL DEFAULT 'tenant' CHECK (scope IN ('tenant', 'client')),
    -- Only set when scope = 'client'
    client_id   INTEGER      REFERENCES clients(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Role name must be unique within its scope (tenant-global or per-client)
    CONSTRAINT roles_name_unique_per_scope UNIQUE (tenant_id, scope, client_id, name),
    -- Client-scoped roles must have a client_id
    CONSTRAINT roles_client_scope_check CHECK (
        (scope = 'tenant' AND client_id IS NULL) OR
        (scope = 'client' AND client_id IS NOT NULL)
    )
);

CREATE INDEX idx_roles_tenant ON roles (tenant_id);
CREATE INDEX idx_roles_client ON roles (client_id) WHERE client_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- Composite role mappings — parent role includes child roles
-- A role can include multiple children; cycles must be prevented at app level.
-- -----------------------------------------------------------------------------
CREATE TABLE composite_role_mappings (
    parent_role_id INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    child_role_id  INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,

    PRIMARY KEY (parent_role_id, child_role_id),
    -- A role cannot include itself
    CONSTRAINT composite_no_self_reference CHECK (parent_role_id != child_role_id)
);

-- -----------------------------------------------------------------------------
-- User ↔ Role assignment (many-to-many)
-- -----------------------------------------------------------------------------
CREATE TABLE user_roles (
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id    INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_user ON user_roles (user_id);
CREATE INDEX idx_user_roles_role ON user_roles (role_id);

-- -----------------------------------------------------------------------------
-- Groups — hierarchical, tenant-scoped
-- parent_group_id enables nesting (null = top-level group)
-- -----------------------------------------------------------------------------
CREATE TABLE groups (
    id              SERIAL PRIMARY KEY,
    tenant_id       INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    parent_group_id INTEGER      REFERENCES groups(id) ON DELETE CASCADE,
    attributes      JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Group name must be unique among siblings (same parent within a tenant)
    CONSTRAINT groups_name_unique_per_parent UNIQUE (tenant_id, parent_group_id, name)
);

CREATE INDEX idx_groups_tenant ON groups (tenant_id);
CREATE INDEX idx_groups_parent ON groups (parent_group_id) WHERE parent_group_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- Group ↔ Role assignment — roles inherited by all group members
-- -----------------------------------------------------------------------------
CREATE TABLE group_roles (
    group_id   INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    role_id    INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (group_id, role_id)
);

-- -----------------------------------------------------------------------------
-- User ↔ Group membership (many-to-many)
-- -----------------------------------------------------------------------------
CREATE TABLE user_groups (
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    group_id   INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, group_id)
);

CREATE INDEX idx_user_groups_user ON user_groups (user_id);
CREATE INDEX idx_user_groups_group ON user_groups (group_id);
