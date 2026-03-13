-- =============================================================================
-- V13: Expanded password policies (Phase 3c)
--
-- Adds:
--   1. password_history — prevents reuse of last N passwords
--   2. Tenant-level policy columns for expiry and history depth
--   3. Common password blacklist table
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Password history — stores hashed previous passwords per user
-- Used to enforce "cannot reuse last N passwords" policy.
-- -----------------------------------------------------------------------------
CREATE TABLE password_history (
    id            SERIAL PRIMARY KEY,
    user_id       INTEGER      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id     INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    password_hash VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_password_history_user ON password_history (user_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- Tenant-level password policy extensions
-- -----------------------------------------------------------------------------
ALTER TABLE tenants
    ADD COLUMN password_policy_history_count   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN password_policy_max_age_days    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN password_policy_require_uppercase BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN password_policy_require_number    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN password_policy_blacklist_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- 0 = disabled for both history_count and max_age_days

-- -----------------------------------------------------------------------------
-- Common password blacklist
-- Pre-populated with top common passwords. Admins can add/remove entries.
-- Tenant-scoped so each workspace can customize.
-- NULL tenant_id = global (applies to all tenants).
-- -----------------------------------------------------------------------------
CREATE TABLE password_blacklist (
    id        SERIAL PRIMARY KEY,
    tenant_id INTEGER REFERENCES tenants(id) ON DELETE CASCADE,
    password  VARCHAR(255) NOT NULL,

    CONSTRAINT password_blacklist_unique UNIQUE (tenant_id, password)
);

CREATE INDEX idx_password_blacklist_tenant ON password_blacklist (tenant_id);

-- Seed global common passwords (top 50 most common)
INSERT INTO password_blacklist (tenant_id, password) VALUES
    (NULL, 'password'), (NULL, '123456'), (NULL, '12345678'), (NULL, 'qwerty'),
    (NULL, 'abc123'), (NULL, 'monkey'), (NULL, '1234567'), (NULL, 'letmein'),
    (NULL, 'trustno1'), (NULL, 'dragon'), (NULL, 'baseball'), (NULL, 'iloveyou'),
    (NULL, 'master'), (NULL, 'sunshine'), (NULL, 'ashley'), (NULL, 'bailey'),
    (NULL, 'passw0rd'), (NULL, 'shadow'), (NULL, '123123'), (NULL, '654321'),
    (NULL, 'superman'), (NULL, 'qazwsx'), (NULL, 'michael'), (NULL, 'football'),
    (NULL, 'password1'), (NULL, 'password123'), (NULL, '1234'), (NULL, '12345'),
    (NULL, '1234567890'), (NULL, '000000'), (NULL, 'charlie'), (NULL, 'donald'),
    (NULL, 'admin'), (NULL, 'welcome'), (NULL, 'login'), (NULL, 'princess'),
    (NULL, 'qwerty123'), (NULL, 'solo'), (NULL, 'starwars'), (NULL, 'access'),
    (NULL, 'flower'), (NULL, 'hottie'), (NULL, 'loveme'), (NULL, 'zaq1zaq1'),
    (NULL, 'hello'), (NULL, 'charlie'), (NULL, 'aa123456'), (NULL, 'qwerty1'),
    (NULL, 'password2'), (NULL, 'changeme');
