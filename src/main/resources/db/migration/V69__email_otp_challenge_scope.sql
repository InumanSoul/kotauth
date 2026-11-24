-- V69: Keep the scope an email-code client asked for on its challenge.
--
-- NULL means the client sent none, and the code is issued for `openid email profile`, the
-- scope every challenge carried before this column existed.

ALTER TABLE email_otp_challenges
    ADD COLUMN scope TEXT NULL;
