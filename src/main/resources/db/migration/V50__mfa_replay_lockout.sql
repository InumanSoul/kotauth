-- Adds replay-resistance + per-enrollment lockout state to mfa_enrollments.
-- last_used_step      : RFC 6238 step (timeMillis / 1000 / 30) of the most recently
--                       accepted TOTP code. Verify rejects any code whose accepted step
--                       is <= last_used_step, neutralising same-window replay.
-- failed_mfa_attempts : Consecutive failed TOTP verifications. Reset on success or unlock.
-- mfa_locked_until    : When non-null AND > now(), the enrollment refuses TOTP attempts.
ALTER TABLE mfa_enrollments
    ADD COLUMN last_used_step      BIGINT      NULL,
    ADD COLUMN failed_mfa_attempts INT         NOT NULL DEFAULT 0,
    ADD COLUMN mfa_locked_until    TIMESTAMPTZ NULL;
