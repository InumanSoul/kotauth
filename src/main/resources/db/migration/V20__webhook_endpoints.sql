-- =============================================================================
-- V20: Webhook Endpoints — tenant-configured HTTP callbacks (Phase 4)
--
-- Design:
--   • Each endpoint belongs to one tenant and listens to a comma-separated
--     list of event type strings (e.g. "user.created,login.success").
--   • The plaintext secret is never stored — only the HMAC-SHA256 key used to
--     sign payloads, so receivers can verify authenticity without a round-trip.
--   • Enabled flag allows temporary suspension without deleting the config.
-- =============================================================================

CREATE TABLE webhook_endpoints (
    id            SERIAL PRIMARY KEY,
    tenant_id     INTEGER       NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    -- Target URL that will receive POST requests on matching events
    url           VARCHAR(2048) NOT NULL,
    -- Raw secret stored as-is — used as the HMAC-SHA256 key for X-KotAuth-Signature.
    -- Stored server-side only; the admin sees it once at creation.
    secret        VARCHAR(256)  NOT NULL,
    -- Comma-separated webhook event names subscribed by this endpoint
    -- (e.g. "user.created,login.success"). Empty = subscribe to nothing.
    events        TEXT          NOT NULL DEFAULT '',
    -- Human-readable label for the admin UI
    description   VARCHAR(256)  NOT NULL DEFAULT '',
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_endpoints_tenant ON webhook_endpoints (tenant_id);
CREATE INDEX idx_webhook_endpoints_enabled ON webhook_endpoints (tenant_id, enabled);
