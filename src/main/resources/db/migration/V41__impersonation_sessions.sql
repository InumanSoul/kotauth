-- V41: Admin impersonation — link impersonation sessions to the originating admin session.
--
-- When an admin starts impersonating a user, a new session row is created with
-- user_id = target user. impersonator_session_id points to the admin's own
-- session row. ON DELETE CASCADE ensures impersonation children disappear if
-- the parent session row is ever physically deleted by the cleanup job.
--
-- Logical revocation (revoked_at) is handled at the application layer so that
-- "revoke all sessions for admin" cascades to active impersonation children.

ALTER TABLE sessions
    ADD COLUMN impersonator_session_id INTEGER
        REFERENCES sessions(id) ON DELETE CASCADE;

CREATE INDEX idx_sessions_impersonator
    ON sessions(impersonator_session_id)
    WHERE impersonator_session_id IS NOT NULL;
