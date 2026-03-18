package com.kauth.domain.port

import com.kauth.domain.model.WebhookDelivery
import com.kauth.domain.model.WebhookDeliveryStatus

/**
 * Port — persistence for webhook delivery records (append + update).
 * Implemented by [PostgresWebhookDeliveryRepository].
 */
interface WebhookDeliveryRepository {
    /** Inserts a new delivery record and returns it with the generated [id]. */
    fun save(delivery: WebhookDelivery): WebhookDelivery

    /** Updates [status], [attempts], [lastAttemptAt], and [responseStatus] after an attempt. */
    fun update(delivery: WebhookDelivery)

    /** Returns the most recent deliveries for a given endpoint, ordered by [createdAt] DESC. */
    fun findByEndpointId(
        endpointId: Int,
        limit: Int = 50,
    ): List<WebhookDelivery>

    /** Returns recent deliveries across all endpoints for a tenant (for the admin overview). */
    fun findByTenantId(
        tenantId: Int,
        limit: Int = 100,
    ): List<WebhookDelivery>

    /** Returns a single delivery by its own ID. */
    fun findById(id: Int): WebhookDelivery?

    /** Returns deliveries that are still [WebhookDeliveryStatus.PENDING] (for retry sweeps). */
    fun findPending(limit: Int = 50): List<WebhookDelivery>
}
