package com.kauth.domain.service

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.WebhookEventType
import com.kauth.fakes.FakeWebhookDeliveryRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [WebhookService].
 *
 * Covers: endpoint CRUD, validation, dispatch delivery record creation,
 * and delivery query operations.
 *
 * Note: the async HTTP delivery machinery (coroutine-based retries, actual
 * HTTP calls) is intentionally not tested here — those are integration concerns.
 * These tests validate the synchronous domain logic.
 */
class WebhookServiceTest {
    private val endpoints = FakeWebhookEndpointRepository()
    private val deliveries = FakeWebhookDeliveryRepository()

    private val svc =
        WebhookService(
            endpointRepository = endpoints,
            deliveryRepository = deliveries,
        )

    @BeforeTest
    fun setup() {
        endpoints.clear()
        deliveries.clear()
    }

    // =========================================================================
    // createEndpoint — validation
    // =========================================================================

    @Test
    fun `createEndpoint - blank URL`() {
        val result =
            svc.createEndpoint(
                tenantId = TenantId(1),
                url = "  ",
                events = setOf(WebhookEventType.USER_CREATED),
            )
        assertIs<WebhookResult.Failure>(result)
        assertTrue(result.error.contains("blank"), "Error should mention blank URL")
    }

    @Test
    fun `createEndpoint - URL without http scheme`() {
        val result =
            svc.createEndpoint(
                tenantId = TenantId(1),
                url = "ftp://example.com/hook",
                events = setOf(WebhookEventType.USER_CREATED),
            )
        assertIs<WebhookResult.Failure>(result)
        assertTrue(result.error.contains("http"), "Error should mention http requirement")
    }

    @Test
    fun `createEndpoint - URL exceeds 2048 characters`() {
        val longUrl = "https://example.com/" + "a".repeat(2040)
        val result =
            svc.createEndpoint(
                tenantId = TenantId(1),
                url = longUrl,
                events = setOf(WebhookEventType.USER_CREATED),
            )
        assertIs<WebhookResult.Failure>(result)
        assertTrue(result.error.contains("2048"), "Error should mention length limit")
    }

    @Test
    fun `createEndpoint - success returns endpoint and plaintext secret`() {
        val result =
            svc.createEndpoint(
                tenantId = TenantId(1),
                url = "https://example.com/hook",
                events = setOf(WebhookEventType.USER_CREATED, WebhookEventType.USER_DELETED),
                description = "Test hook",
            )
        assertIs<WebhookResult.Success>(result)
        val ep = result.endpoint
        assertNotNull(ep.id)
        assertEquals(TenantId(1), ep.tenantId)
        assertEquals("https://example.com/hook", ep.url)
        assertEquals(
            setOf(WebhookEventType.USER_CREATED, WebhookEventType.USER_DELETED),
            ep.events,
        )
        assertEquals("Test hook", ep.description)
        assertTrue(ep.enabled)
        assertTrue(result.plaintextSecret.isNotBlank(), "Secret must be non-blank")
    }

    @Test
    fun `createEndpoint - http URL is accepted`() {
        val result =
            svc.createEndpoint(
                tenantId = TenantId(1),
                url = "http://localhost:8080/hook",
                events = setOf(WebhookEventType.LOGIN_SUCCESS),
            )
        assertIs<WebhookResult.Success>(result)
    }

    // =========================================================================
    // listEndpoints
    // =========================================================================

    @Test
    fun `listEndpoints - returns only endpoints for the given tenant`() {
        svc.createEndpoint(
            tenantId = TenantId(1),
            url = "https://a.com/hook",
            events = setOf(WebhookEventType.USER_CREATED),
        )
        svc.createEndpoint(
            tenantId = TenantId(1),
            url = "https://b.com/hook",
            events = setOf(WebhookEventType.USER_UPDATED),
        )
        svc.createEndpoint(
            tenantId = TenantId(2),
            url = "https://c.com/hook",
            events = setOf(WebhookEventType.USER_CREATED),
        )

        assertEquals(2, svc.listEndpoints(TenantId(1)).size)
        assertEquals(1, svc.listEndpoints(TenantId(2)).size)
        assertEquals(0, svc.listEndpoints(TenantId(99)).size)
    }

    // =========================================================================
    // deleteEndpoint
    // =========================================================================

    @Test
    fun `deleteEndpoint - removes endpoint for correct tenant`() {
        val created =
            (
                svc.createEndpoint(
                    TenantId(1),
                    "https://a.com/hook",
                    setOf(WebhookEventType.USER_CREATED),
                ) as WebhookResult.Success
            ).endpoint
        assertEquals(1, svc.listEndpoints(TenantId(1)).size)

        svc.deleteEndpoint(created.id!!, tenantId = TenantId(1))
        assertEquals(0, svc.listEndpoints(TenantId(1)).size)
    }

    @Test
    fun `deleteEndpoint - wrong tenant does not delete`() {
        val created =
            (
                svc.createEndpoint(
                    TenantId(1),
                    "https://a.com/hook",
                    setOf(WebhookEventType.USER_CREATED),
                ) as WebhookResult.Success
            ).endpoint

        svc.deleteEndpoint(created.id!!, tenantId = TenantId(99))
        assertEquals(
            1,
            svc.listEndpoints(TenantId(1)).size,
            "Endpoint should not be deleted for wrong tenant",
        )
    }

    // =========================================================================
    // toggleEndpoint
    // =========================================================================

    @Test
    fun `toggleEndpoint - disables and re-enables endpoint`() {
        val created =
            (
                svc.createEndpoint(
                    TenantId(1),
                    "https://a.com/hook",
                    setOf(WebhookEventType.USER_CREATED),
                ) as WebhookResult.Success
            ).endpoint
        assertTrue(created.enabled)

        svc.toggleEndpoint(created.id!!, tenantId = TenantId(1), enabled = false)
        val disabled = svc.listEndpoints(TenantId(1)).first()
        assertEquals(false, disabled.enabled)

        svc.toggleEndpoint(created.id!!, tenantId = TenantId(1), enabled = true)
        val reenabled = svc.listEndpoints(TenantId(1)).first()
        assertEquals(true, reenabled.enabled)
    }

    // =========================================================================
    // dispatch — delivery record creation
    // =========================================================================

    @Test
    fun `dispatch - creates delivery records for matching enabled endpoints`() {
        svc.createEndpoint(TenantId(1), "https://a.com/hook", setOf(WebhookEventType.USER_CREATED))
        svc.createEndpoint(
            TenantId(1),
            "https://b.com/hook",
            setOf(WebhookEventType.USER_CREATED, WebhookEventType.USER_DELETED),
        )

        svc.dispatch(
            tenantId = TenantId(1),
            eventType = WebhookEventType.USER_CREATED,
            payloadData = mapOf("userId" to 42),
        )

        // Give coroutines a moment — but delivery records are created synchronously before launch
        val allDeliveries = deliveries.all()
        assertEquals(2, allDeliveries.size, "Should create one delivery per matching endpoint")
        allDeliveries.forEach { d ->
            assertEquals(WebhookEventType.USER_CREATED, d.eventType)
            assertTrue(d.payload.contains("user.created"))
            assertNotNull(d.id)
        }
    }

    @Test
    fun `dispatch - does not create deliveries for unsubscribed event`() {
        svc.createEndpoint(TenantId(1), "https://a.com/hook", setOf(WebhookEventType.USER_CREATED))

        svc.dispatch(
            tenantId = TenantId(1),
            eventType = WebhookEventType.LOGIN_SUCCESS,
            payloadData = emptyMap(),
        )

        assertEquals(0, deliveries.all().size)
    }

    @Test
    fun `dispatch - does not create deliveries for disabled endpoints`() {
        val created =
            (
                svc.createEndpoint(
                    TenantId(1),
                    "https://a.com/hook",
                    setOf(WebhookEventType.USER_CREATED),
                ) as WebhookResult.Success
            ).endpoint
        svc.toggleEndpoint(created.id!!, tenantId = TenantId(1), enabled = false)

        svc.dispatch(
            tenantId = TenantId(1),
            eventType = WebhookEventType.USER_CREATED,
            payloadData = emptyMap(),
        )

        assertEquals(0, deliveries.all().size)
    }

    @Test
    fun `dispatch - no endpoints for tenant is a no-op`() {
        svc.dispatch(
            tenantId = TenantId(99),
            eventType = WebhookEventType.USER_CREATED,
            payloadData = emptyMap(),
        )
        assertEquals(0, deliveries.all().size)
    }

    // =========================================================================
    // recentDeliveries / deliveriesForEndpoint
    // =========================================================================

    @Test
    fun `recentDeliveries - returns deliveries for tenant`() {
        svc.createEndpoint(TenantId(1), "https://a.com/hook", setOf(WebhookEventType.USER_CREATED))
        svc.dispatch(
            tenantId = TenantId(1),
            eventType = WebhookEventType.USER_CREATED,
            payloadData = mapOf("id" to 1),
        )
        svc.dispatch(
            tenantId = TenantId(1),
            eventType = WebhookEventType.USER_CREATED,
            payloadData = mapOf("id" to 2),
        )

        val recent = svc.recentDeliveries(tenantId = TenantId(1), limit = 10)
        assertEquals(2, recent.size)
    }

    @Test
    fun `deliveriesForEndpoint - scoped to specific endpoint`() {
        val ep1 =
            (
                svc.createEndpoint(
                    TenantId(1),
                    "https://a.com/hook",
                    setOf(WebhookEventType.USER_CREATED),
                ) as WebhookResult.Success
            ).endpoint
        val ep2 =
            (
                svc.createEndpoint(
                    TenantId(1),
                    "https://b.com/hook",
                    setOf(WebhookEventType.USER_CREATED),
                ) as WebhookResult.Success
            ).endpoint

        svc.dispatch(
            tenantId = TenantId(1),
            eventType = WebhookEventType.USER_CREATED,
            payloadData = mapOf("id" to 1),
        )

        val ep1Deliveries = svc.deliveriesForEndpoint(ep1.id!!, limit = 10)
        val ep2Deliveries = svc.deliveriesForEndpoint(ep2.id!!, limit = 10)

        assertEquals(1, ep1Deliveries.size)
        assertEquals(1, ep2Deliveries.size)
        assertEquals(ep1.id, ep1Deliveries.first().endpointId)
        assertEquals(ep2.id, ep2Deliveries.first().endpointId)
    }
}
