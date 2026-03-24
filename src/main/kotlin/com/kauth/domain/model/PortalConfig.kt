package com.kauth.domain.model

/**
 * UI configuration for the self-service portal.
 *
 * Stored in `workspace_portal_config` (1:1 with tenant).
 * Composed into [Tenant] as a value object — callers access it via `tenant.portalConfig`.
 */
data class PortalConfig(
    val layout: PortalLayout = PortalLayout.SIDEBAR,
)
