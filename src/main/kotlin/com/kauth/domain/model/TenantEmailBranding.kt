package com.kauth.domain.model

/**
 * Per-tenant transactional email identity. Absent row = Kotauth defaults.
 * No `from_email` field — envelope sender stays operator-controlled for
 * DKIM/SPF/DMARC alignment (see ADR-15).
 */
data class TenantEmailBranding(
    val id: Int? = null,
    val tenantId: TenantId,
    val brandName: String? = null,
    val brandColorHex: String? = null,
    val brandLogoUrl: String? = null,
    val supportEmail: String? = null,
    val fromDisplayName: String? = null,
)
