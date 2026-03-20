package com.kauth.fakes

import com.kauth.domain.model.WebhookEndpoint
import com.kauth.domain.port.WebhookEndpointRepository

class FakeWebhookEndpointRepository : WebhookEndpointRepository {
    private val store = mutableMapOf<Int, WebhookEndpoint>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    fun add(endpoint: WebhookEndpoint): WebhookEndpoint {
        val e = if (endpoint.id == null) endpoint.copy(id = nextId++) else endpoint
        store[e.id!!] = e
        return e
    }

    override fun save(endpoint: WebhookEndpoint): WebhookEndpoint {
        val e = endpoint.copy(id = nextId++)
        store[e.id!!] = e
        return e
    }

    override fun findByTenantId(tenantId: Int): List<WebhookEndpoint> =
        store.values.filter { it.tenantId == tenantId }.sortedByDescending { it.createdAt }

    override fun findEnabledByTenantAndEvent(tenantId: Int, eventType: String): List<WebhookEndpoint> =
        store.values.filter {
            it.tenantId == tenantId && it.enabled && eventType in it.events
        }

    override fun findById(id: Int, tenantId: Int): WebhookEndpoint? =
        store[id]?.takeIf { it.tenantId == tenantId }

    override fun update(endpoint: WebhookEndpoint) {
        endpoint.id?.let { store[it] = endpoint }
    }

    override fun delete(id: Int, tenantId: Int) {
        val ep = store[id]
        if (ep != null && ep.tenantId == tenantId) store.remove(id)
    }

    override fun setEnabled(id: Int, tenantId: Int, enabled: Boolean) {
        val ep = store[id]
        if (ep != null && ep.tenantId == tenantId) {
            store[id] = ep.copy(enabled = enabled)
        }
    }
}
