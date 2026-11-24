-- V68: Keep how a sign-in was proven, for the OIDC `amr` and `acr` claims.
--
-- An authorization code records the RFC 8176 methods of the sign-in that produced it, and the
-- session minted from that code keeps them along with `auth_time`, so a refreshed ID token
-- reports the same sign-in as the first one (OIDC Core §12.2). Rows written before this
-- migration have no methods and no auth time; their tokens simply omit the claims.

ALTER TABLE authorization_codes ADD COLUMN amr JSONB NOT NULL DEFAULT '[]';

ALTER TABLE sessions ADD COLUMN auth_time TIMESTAMPTZ;
ALTER TABLE sessions ADD COLUMN amr JSONB NOT NULL DEFAULT '[]';
