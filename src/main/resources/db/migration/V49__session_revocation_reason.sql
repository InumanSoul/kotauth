-- Refresh-token replay detection (OAuth 2.0 Security BCP §4.14.2).
-- Distinguishes sessions revoked by refresh rotation ('rotated') from
-- logout/admin revocation, so replaying a rotated refresh token can be
-- treated as theft without benign post-logout retries cascading.
ALTER TABLE sessions ADD COLUMN revocation_reason VARCHAR(32);
