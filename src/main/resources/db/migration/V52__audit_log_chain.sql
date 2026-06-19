-- V52: HMAC audit chain (v1.19.0).

ALTER TABLE audit_log
    ADD COLUMN prev_hash    BYTEA NULL,
    ADD COLUMN row_hash     BYTEA NULL,
    ADD COLUMN chain_key_id VARCHAR(32) NULL;

CREATE INDEX idx_audit_log_tenant_id_id ON audit_log (tenant_id, id DESC);
