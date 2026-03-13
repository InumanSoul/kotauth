package com.kauth.domain.model

import java.time.Instant

/**
 * An immutable audit log entry describing a security-relevant event.
 *
 * Audit events are append-only — they are never updated or deleted.
 * The [details] map carries arbitrary per-event metadata (scopes, error
 * reasons, client names, etc.) serialized to JSONB in the database.
 */
data class AuditEvent(
    val tenantId: Int?,
    val userId: Int?,
    val clientId: Int?,
    val eventType: AuditEventType,
    val ipAddress: String?,
    val userAgent: String?,
    val details: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now()
)

enum class AuditEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    LOGIN_RATE_LIMITED,

    REGISTER_SUCCESS,
    REGISTER_FAILED,

    TOKEN_ISSUED,
    TOKEN_REFRESHED,
    TOKEN_REVOKED,
    TOKEN_INTROSPECTED,

    AUTHORIZATION_CODE_ISSUED,
    AUTHORIZATION_CODE_USED,
    AUTHORIZATION_CODE_EXPIRED,

    SESSION_CREATED,
    SESSION_REVOKED,

    ADMIN_TENANT_CREATED,
    ADMIN_TENANT_UPDATED,
    ADMIN_CLIENT_CREATED,
    ADMIN_CLIENT_UPDATED,
    ADMIN_CLIENT_SECRET_REGENERATED,
    ADMIN_CLIENT_ENABLED,
    ADMIN_CLIENT_DISABLED,
    ADMIN_USER_CREATED,
    ADMIN_USER_UPDATED,
    ADMIN_USER_ENABLED,
    ADMIN_USER_DISABLED,
    ADMIN_SESSION_REVOKED,

    // Phase 3b — email flows
    EMAIL_VERIFICATION_SENT,
    EMAIL_VERIFIED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,

    // Phase 3b — self-service portal
    USER_PROFILE_UPDATED,
    USER_PASSWORD_CHANGED,
    USER_SESSION_REVOKED_SELF,

    // Phase 3b — admin-initiated
    ADMIN_USER_PASSWORD_RESET,
    ADMIN_SMTP_UPDATED
}
