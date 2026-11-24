-- V71: A user's OIDC subject (`sub`) becomes its own column instead of the row id.
--
-- The row id is assigned by each database, so a workspace exported from one instance and
-- imported into another gave every user a new `sub`, breaking every relying party that keys its
-- records on `iss` + `sub`. Row ids are also sequential, so a `sub` revealed how many users an
-- instance had and let one be guessed from another.
--
-- Existing users keep the subject they already had (their row id), so no token, link or
-- integration changes. New users get a random UUID. Import writes the subject the export carries.

ALTER TABLE users ADD COLUMN subject TEXT;

UPDATE users SET subject = id::text;

ALTER TABLE users
    ALTER COLUMN subject SET NOT NULL,
    ALTER COLUMN subject SET DEFAULT gen_random_uuid()::text,
    ADD CONSTRAINT users_subject_per_tenant UNIQUE (tenant_id, subject);
