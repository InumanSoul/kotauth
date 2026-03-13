-- V8: Audit log — immutable event trail
--
-- Records security-relevant events: logins, logouts, registrations, token
-- issuance, revocations, admin actions, rate limit hits.
--
-- event_type values (exhaustive list grows with features):
--   LOGIN_SUCCESS, LOGIN_FAILED, LOGIN_RATE_LIMITED
--   REGISTER_SUCCESS, REGISTER_FAILED
--   TOKEN_ISSUED, TOKEN_REFRESHED, TOKEN_REVOKED, TOKEN_INTROSPECTED
--   AUTHORIZATION_CODE_ISSUED, AUTHORIZATION_CODE_USED, AUTHORIZATION_CODE_EXPIRED
--   SESSION_CREATED, SESSION_REVOKED
--   ADMIN_TENANT_CREATED, ADMIN_CLIENT_CREATED, ADMIN_USER_DISABLED
--
-- details is JSONB for flexible per-event metadata (client_id, scopes, error reason, etc.)
-- Rows in this table are NEVER updated or deleted (append-only by design).

CREATE TABLE audit_log (
    id          SERIAL      PRIMARY KEY,
    tenant_id   INTEGER     REFERENCES tenants(id) ON DELETE SET NULL,
    user_id     INTEGER     REFERENCES users(id)   ON DELETE SET NULL,
    client_id   INTEGER     REFERENCES clients(id) ON DELETE SET NULL,
    event_type  VARCHAR(64) NOT NULL,
    ip_address  VARCHAR(45),
    user_agent  TEXT,
    details     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_tenant_id   ON audit_log(tenant_id);
CREATE INDEX idx_audit_user_id     ON audit_log(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_audit_event_type  ON audit_log(event_type);
CREATE INDEX idx_audit_created_at  ON audit_log(created_at);
