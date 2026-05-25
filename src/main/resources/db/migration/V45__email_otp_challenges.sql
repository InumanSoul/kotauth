-- V45: Email OTP challenges (v1.12.0).
-- Raw 6-digit code never stored — only its SHA-256. challenge_id is the public
-- opaque handle; id stays internal. originating_client_id flows to the v1.11.0
-- default-roles grant and audience selection at verify time.

CREATE TABLE email_otp_challenges (
    id                      SERIAL       PRIMARY KEY,
    user_id                 INTEGER      NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    tenant_id               INTEGER      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    challenge_id            VARCHAR(64)  NOT NULL UNIQUE,
    code_hash               VARCHAR(64)  NOT NULL,
    originating_client_id   VARCHAR(255),
    attempt_count           INTEGER      NOT NULL DEFAULT 0,
    resend_count            INTEGER      NOT NULL DEFAULT 0,
    expires_at              TIMESTAMPTZ  NOT NULL,
    consumed_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_email_otp_challenges_user   ON email_otp_challenges (user_id);
CREATE INDEX idx_email_otp_challenges_expiry ON email_otp_challenges (expires_at);

-- email_otp_signup_enabled gates find-or-create (default off — no new
-- account-creation surface on upgrade). email_otp_lockout_threshold trips the
-- existing locked_until window after N cross-challenge failures.
ALTER TABLE tenant_security_config
    ADD COLUMN email_otp_signup_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN email_otp_lockout_threshold INTEGER NOT NULL DEFAULT 5;

-- Separate from failed_login_attempts so OTP and password lockout analytics
-- stay independent and operators can tune the two thresholds separately.
ALTER TABLE users
    ADD COLUMN failed_otp_challenges INTEGER NOT NULL DEFAULT 0;
