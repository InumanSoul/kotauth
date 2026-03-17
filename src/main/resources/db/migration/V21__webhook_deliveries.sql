-- =============================================================================
-- V21: Webhook Deliveries — per-event delivery records with retry state (Phase 4)
--
-- Design:
--   • Each delivery record tracks one dispatch attempt for one event to one endpoint.
--   • Status lifecycle: pending → delivered | failed
--   • Up to 3 delivery attempts with exponential backoff (immediate, 5 min, 30 min).
--   • response_status captures the HTTP status code from the last attempt.
--     NULL means the request never got a response (network error, timeout).
--   • Payload is stored as JSONB for queryability (admin UI filtering).
--   • status is VARCHAR(16) not a custom ENUM — Exposed maps it via varchar column
--     which avoids PGobject wrapping. Domain model enforces valid values.
-- =============================================================================

CREATE TABLE webhook_deliveries (
    id               SERIAL PRIMARY KEY,
    endpoint_id      INTEGER      NOT NULL REFERENCES webhook_endpoints(id) ON DELETE CASCADE,
    -- Webhook event type string (e.g. "user.created")
    event_type       VARCHAR(64)  NOT NULL,
    -- Full JSON payload sent to the endpoint
    payload          JSONB        NOT NULL,
    -- Delivery state: pending | delivered | failed
    status           VARCHAR(16)  NOT NULL DEFAULT 'pending',
    -- Total number of delivery attempts made so far
    attempts         INTEGER      NOT NULL DEFAULT 0,
    -- Timestamp of the most recent attempt (NULL = not yet attempted)
    last_attempt_at  TIMESTAMPTZ,
    -- HTTP status code returned by the endpoint on the last attempt (NULL = no response)
    response_status  INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_deliveries_endpoint ON webhook_deliveries (endpoint_id);
CREATE INDEX idx_webhook_deliveries_status   ON webhook_deliveries (status);
CREATE INDEX idx_webhook_deliveries_created  ON webhook_deliveries (created_at DESC);
