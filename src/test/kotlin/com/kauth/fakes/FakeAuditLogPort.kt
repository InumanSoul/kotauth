package com.kauth.fakes

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.port.AuditLogPort

/**
 * In-memory AuditLogPort for unit tests.
 * Captures recorded events so tests can assert which events were fired.
 */
class FakeAuditLogPort : AuditLogPort {
    private val _events = mutableListOf<AuditEvent>()

    val events: List<AuditEvent> get() = _events.toList()

    fun clear() {
        _events.clear()
    }

    /** Convenience: count events of a specific type. */
    fun countOf(type: AuditEventType) = _events.count { it.eventType == type }

    /** Convenience: check if a specific event was recorded. */
    fun hasEvent(type: AuditEventType) = _events.any { it.eventType == type }

    override fun record(event: AuditEvent) {
        _events.add(event)
    }
}
