-- v1.8.1 OIDC SSO: record the moment the user proved their credentials so the
-- ID token issued from this code can carry the OIDC `auth_time` claim. NULL is
-- allowed for codes issued before this column existed; new issuance always
-- populates it.
ALTER TABLE authorization_codes
    ADD COLUMN auth_time TIMESTAMP WITH TIME ZONE;
