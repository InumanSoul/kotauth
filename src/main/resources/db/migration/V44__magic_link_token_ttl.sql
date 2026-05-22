-- V44: Per-tenant magic-link token TTL.
--
-- Magic-link expiry was a hardcoded 15 minutes. High-assurance tenants may
-- want shorter links; tenants on slow corporate mail relays may need longer
-- to avoid expiry-before-delivery. Range 1-1440 minutes is enforced at the
-- service layer (AdminService.updateWorkspaceSettings).

ALTER TABLE tenant_security_config
    ADD COLUMN magic_link_token_ttl_minutes INTEGER NOT NULL DEFAULT 15;
