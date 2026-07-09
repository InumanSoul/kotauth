-- Per-API scope catalogue. NULL/empty array = no narrowing (legacy behaviour).
ALTER TABLE resource_servers
    ADD COLUMN scopes JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Resources bound at /authorize-time, read back at /token to issue aud-targeted access token.
ALTER TABLE authorization_codes
    ADD COLUMN resources JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Resources persisted on the issued session so refresh_token grant can reissue
-- with the same audiences. RFC 8707 §3 allows refresh to narrow but not widen.
ALTER TABLE sessions
    ADD COLUMN resources JSONB NOT NULL DEFAULT '[]'::jsonb;
