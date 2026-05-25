-- V46: Per-tenant transactional email branding (v1.12.0).
-- 1:1 with tenants; absent row = Kotauth defaults. from_email omitted on
-- purpose — envelope sender stays operator-controlled for DKIM/SPF alignment.

CREATE TABLE tenant_email_branding (
    id                  SERIAL      PRIMARY KEY,
    tenant_id           INTEGER     NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    brand_name          VARCHAR(128),
    brand_color_hex     VARCHAR(7),
    brand_logo_url      TEXT,
    support_email       VARCHAR(255),
    from_display_name   VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
