-- V43: Custom token audience per client.
--
-- When set, JwtTokenAdapter uses this value for the access-token `aud` claim
-- instead of the client_id. Lets one OAuth client mint tokens for a resource
-- server whose identifier differs from the client_id, without overloading
-- client_id itself.
--
-- Null = fall back to client_id, then tenant slug — the pre-v1.11 behavior.
-- This is deliberately not RFC 8707 Resource Indicators; it is a single
-- configurable audience value.

ALTER TABLE clients ADD COLUMN audience VARCHAR(200);
