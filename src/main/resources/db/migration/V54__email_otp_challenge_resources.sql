-- V54: Bind RFC 8707 resource indicators to email-OTP challenges.
--
-- Email OTP is a back-channel authorization-code issuer. Store the resources
-- requested by the originating client when the code is sent, then copy them to
-- the authorization code after OTP verification so /token can mint an
-- audience-targeted access token while keeping id_token.aud on the OAuth client.

ALTER TABLE email_otp_challenges
    ADD COLUMN resources JSONB NOT NULL DEFAULT '[]'::jsonb;
