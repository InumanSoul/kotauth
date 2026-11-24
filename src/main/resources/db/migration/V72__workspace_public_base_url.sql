-- V72: An optional public origin per workspace (e.g. https://auth.product.com).
--
-- When set, the workspace's issuer, discovery document, sign-in and account pages, and every link
-- Kotauth sends its users are built on it instead of KAUTH_BASE_URL. NULL keeps the instance origin.

ALTER TABLE tenants ADD COLUMN public_base_url VARCHAR(255) NULL;
