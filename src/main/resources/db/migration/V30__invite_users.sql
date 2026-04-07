-- V30: Invite Users support
-- Adds required_actions to users and purpose to password_reset_tokens.

ALTER TABLE users
    ADD COLUMN required_actions text[] NOT NULL DEFAULT '{}';

ALTER TABLE password_reset_tokens
    ADD COLUMN purpose varchar(32) NOT NULL DEFAULT 'PASSWORD_RESET';

-- Partial index: find active invite tokens for a user efficiently
CREATE INDEX idx_prt_user_purpose_active
    ON password_reset_tokens(user_id, purpose)
    WHERE used_at IS NULL;
