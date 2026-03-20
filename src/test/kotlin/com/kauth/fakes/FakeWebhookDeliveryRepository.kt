package com.kauth.fakes

import com.kauth.domain.model.WebhookDelivery
import com.kauth.domain.model.WebhookDeliveryStatus
import com.kauth.domain.port.WebhookDeliveryRepository

class FakeWebhookDeliveryRepository : WebhookDeliveryRepository {
    private val store = mutableMapOf<Int, WebhookDelivery>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    fun all(): List<WebhookDelivery> = store.values.toList()

    override fun save(delivery: WebhookDelivery): WebhookDelivery {
        val d = delivery.copy(id = nextId++)
        store[d.id!!] = d
        return d
    }

    override fun update(delivery: WebhookDelivery) {
        delivery.id?.let { store[it] = delivery }
    }

    override fun findByEndpointId(endpointId: Int, limit: Int): List<WebhookDelivery> =
        store.values
            .filter { it.endpointId == endpointId }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun findByTenantId(tenantId: Int, limit: Int): List<WebhookDelivery> {
        // In production this joins on endpoint.tenantId — here we need the endpoint repo
        // For simplicity, return all deliveries (tests scope by endpointId anyway)
        return store.values.sortedByDescending { it.createdAt }.take(limit)
    }

    override fun findById(id: Int): WebhookDelivery? = store[id]

    override fun findPending(limit: Int): List<WebhookDelivery> =
        store.values
            .filter { it.status == WebhookDeliveryStatus.PENDING }
            .sortedBy { it.createdAt }
            .take(limit)
}
