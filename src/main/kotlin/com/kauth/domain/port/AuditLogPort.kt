package com.kauth.domain.port

import com.kauth.domain.model.AuditEvent

/**
 * Port — audit event persistence (append-only).
 * Implemented by [PostgresAuditLogAdapter].
 *
 * Fire-and-forget: failures here MUST NOT surface to the caller as errors.
 * Audit logging is critical for compliance but should never block authentication.
 */
interface AuditLogPort {
    /**
     * Records a security event. Implementations must swallow all exceptions
     * and log them locally to avoid disrupting the auth flow.
     */
    fun record(event: AuditEvent)
}
