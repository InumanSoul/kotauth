package com.kauth.adapter.web.admin

import com.kauth.infrastructure.EncryptionService
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class FlashCookiesTest {
    private val key = "test-secret-key-32chars-long-xxxx"

    // Two instances sharing the key stand in for two replicas behind a load balancer.
    private val replicaA = FlashCookies(EncryptionService(key), secure = false)
    private val replicaB = FlashCookies(EncryptionService(key), secure = false)

    private fun flashApp(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            routing {
                get("/admin/put") {
                    replicaA.put(call, "client-secret:acme/app", "alice", "s3cret")
                    call.respondText("ok")
                }
                get("/admin/take") {
                    val purpose = call.request.queryParameters["purpose"] ?: "client-secret:acme/app"
                    val admin = call.request.queryParameters["admin"] ?: "alice"
                    call.respondText(replicaB.take(call, purpose, admin) ?: "none")
                }
            }
            block(createClient { install(HttpCookies) })
        }

    @Test
    fun `a value written on one replica is read on another, once`() =
        flashApp { client ->
            client.get("/admin/put")

            assertEquals("s3cret", client.get("/admin/take").bodyAsText())
            assertEquals("none", client.get("/admin/take").bodyAsText())
        }

    @Test
    fun `a value is not shown for another purpose or another admin, and stays for its own`() =
        flashApp { client ->
            client.get("/admin/put")

            assertEquals("none", client.get("/admin/take?purpose=temp-password:acme/7").bodyAsText())
            assertEquals("none", client.get("/admin/take?admin=mallory").bodyAsText())
            assertEquals("s3cret", client.get("/admin/take").bodyAsText())
        }

    @Test
    fun `a cookie encrypted under another key is ignored`() =
        testApplication {
            val foreign = FlashCookies(EncryptionService("another-secret-key-32chars-long-x"), secure = false)
            routing {
                get("/admin/put") {
                    foreign.put(call, "client-secret:acme/app", "alice", "s3cret")
                    call.respondText("ok")
                }
                get("/admin/take") {
                    call.respondText(replicaB.take(call, "client-secret:acme/app", "alice") ?: "none")
                }
            }
            val client = createClient { install(HttpCookies) }
            client.get("/admin/put")

            assertEquals("none", client.get("/admin/take").bodyAsText())
        }
}
