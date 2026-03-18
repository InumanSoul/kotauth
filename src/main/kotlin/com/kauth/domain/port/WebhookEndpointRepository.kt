package com.kauth.domain.port

import com.kauth.domain.model.WebhookEndpoint

/**
 * Port — CRUD for webhook endpoint configuration.
 * Implemented by [PostgresWebhookEndpointRepository].
 */
interface WebhookEndpointRepository {
    /** Persists a new endpoint and returns it with the generated [id]. */
    fun save(endpoint: WebhookEndpoint): WebhookEndpoint

    /** Returns all endpoints for a tenant, ordered by [createdAt] DESC. */
    fun findByTenantId(tenantId: Int): List<WebhookEndpoint>

    /** Returns all *enabled* endpoints subscribed to [eventType] for [tenantId]. */
    fun findEnabledByTenantAndEvent(
        tenantId: Int,
        eventType: String,
    ): List<WebhookEndpoint>

    /** Returns a single endpoint scoped to its tenant. */
    fun findById(
        id: Int,
        tenantId: Int,
    ): WebhookEndpoint?

    /** Replaces the mutable fields of an existing endpoint (url, events, description, enabled). */
    fun update(endpoint: WebhookEndpoint)

    /** Permanently deletes an endpoint and cascades to its delivery records. */
    fun delete(
        id: Int,
        tenantId: Int,
    )

    /** Toggles the [enabled] flag — convenience for the admin UI toggle action. */
    fun setEnabled(
        id: Int,
        tenantId: Int,
        enabled: Boolean,
    )
}
