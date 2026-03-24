package com.kauth.domain.model

/**
 * Layout variant for the self-service portal.
 *
 * SIDEBAR  — fixed 220px left sidebar with vertical nav links (current default).
 * CENTERED — sticky topbar with horizontal tab strip, content centered below.
 */
enum class PortalLayout {
    SIDEBAR,
    CENTERED,
}
