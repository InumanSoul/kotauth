-- V48: Per-tenant toggle for the hosted login-page Email OTP flow (v1.13.0).
-- Independent of email_otp_signup_enabled, which gates the BFF find-or-create path.

ALTER TABLE tenant_security_config
    ADD COLUMN email_otp_login_enabled BOOLEAN NOT NULL DEFAULT FALSE;
