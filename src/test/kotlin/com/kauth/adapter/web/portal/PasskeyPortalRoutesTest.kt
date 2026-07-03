package com.kauth.adapter.web.portal

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.WebAuthnService
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.infrastructure.EncryptionService
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasskeyPortalRoutesTest {
    private val encryptionService = EncryptionService("test-secret-key-32chars-long-xxxx")
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val credentialRepo = FakeWebAuthnCredentialRepository()
    private val relyingParty = FakeRelyingPartyAdapter()
    private val auditLog = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            passkeysEnabled = true,
        )

    private val user =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@acme.dev",
            fullName = "Alice",
            passwordHash = hasher.hash("secret"),
            enabled = true,
        )

    private fun buildWebAuthnService() =
        WebAuthnService(
            credentialRepository = credentialRepo,
            relyingParty = relyingParty,
            secretKey = "test-secret-key-32chars-long-xxxx",
            auditLog = auditLog,
            userRepository = userRepo,
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        credentialRepo.clear()
        auditLog.clear()
        relyingParty.queueRegistration()
        tenantRepo.add(tenant)
        userRepo.add(user)
    }

    // =========================================================================
    // POST /passkeys/register/start
    // =========================================================================

    @Test
    fun `POST passkeys register start returns options and sets challenge cookie`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            loginAs(authed)

            val response = authed.post("/t/acme/passkeys/register/start")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["publicKey"] != null, "Response must contain publicKey field")
            val setCookie = response.headers[HttpHeaders.SetCookie].orEmpty()
            assertTrue(setCookie.contains("KOTAUTH_PASSKEY_REG="), "Must set KOTAUTH_PASSKEY_REG cookie")
        }

    @Test
    fun `POST passkeys register start returns 401 when not authenticated`() =
        testApplication {
            application { installTestApp() }

            val response = client.post("/t/acme/passkeys/register/start")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // =========================================================================
    // POST /passkeys/register/finish
    // =========================================================================

    @Test
    fun `POST passkeys register finish rejects with InvalidChallenge when cookie missing`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            loginAs(authed)

            val response =
                authed.post("/t/acme/passkeys/register/finish") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"credential":{},"name":"My Key"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("InvalidChallenge", body["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST passkeys register finish persists credential when challenge cookie valid`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            loginAs(authed)

            // Step 1: start registration to receive the signed cookie
            val startResponse = authed.post("/t/acme/passkeys/register/start")
            assertEquals(HttpStatusCode.OK, startResponse.status)

            // Step 2: finish registration — FakeRelyingPartyAdapter accepts any credential JSON
            val finishResponse =
                authed.post("/t/acme/passkeys/register/finish") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"credential":{"type":"public-key","id":"Y2FubmVkLWNyZWRlbnRpYWwtaWQ"},"name":"My Passkey"}""",
                    )
                }

            assertEquals(HttpStatusCode.Created, finishResponse.status)
            val body = Json.parseToJsonElement(finishResponse.bodyAsText()).jsonObject
            assertEquals("My Passkey", body["name"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST passkeys register finish returns 401 when not authenticated`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/passkeys/register/finish") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"credential":{},"name":"test"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // =========================================================================
    // GET /passkeys
    // =========================================================================

    @Test
    fun `GET passkeys returns enrolled credentials`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            loginAs(authed)

            // Seed 2 credentials via start+finish twice
            relyingParty.queueRegistration()
            authed.post("/t/acme/passkeys/register/start")
            authed.post("/t/acme/passkeys/register/finish") {
                contentType(ContentType.Application.Json)
                setBody("""{"credential":{"type":"public-key","id":"cred1"},"name":"Key 1"}""")
            }
            relyingParty.queueRegistration()
            authed.post("/t/acme/passkeys/register/start")
            authed.post("/t/acme/passkeys/register/finish") {
                contentType(ContentType.Application.Json)
                setBody("""{"credential":{"type":"public-key","id":"cred2"},"name":"Key 2"}""")
            }

            val response = authed.get("/t/acme/passkeys")

            assertEquals(HttpStatusCode.OK, response.status)
            val list = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(2, list.size)
        }

    @Test
    fun `GET passkeys returns empty list when no credentials enrolled`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            loginAs(authed)

            val response = authed.get("/t/acme/passkeys")

            assertEquals(HttpStatusCode.OK, response.status)
            val list = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(0, list.size)
        }

    @Test
    fun `GET passkeys returns 401 when not authenticated`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/passkeys")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // =========================================================================
    // POST /passkeys/{id}/rename
    // =========================================================================

    @Test
    fun `POST passkey rename updates the name`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            loginAs(authed)

            // Seed a credential
            relyingParty.queueRegistration()
            authed.post("/t/acme/passkeys/register/start")
            val finishResponse =
                authed.post("/t/acme/passkeys/register/finish") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"credential":{"type":"public-key","id":"cred-001"},"name":"Old Name"}""")
                }
            val credId =
                Json
                    .parseToJsonElement(finishResponse.bodyAsText())
                    .jsonObject["id"]
                    ?.jsonPrimitive
                    ?.content
                    ?: throw AssertionError("No id in response")

            // Rename it
            val renameResponse =
                authed.post("/t/acme/passkeys/$credId/rename") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"New Name"}""")
                }

            assertEquals(HttpStatusCode.OK, renameResponse.status)

            // Verify new name appears in list
            val listResponse = authed.get("/t/acme/passkeys")
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val list = Json.parseToJsonElement(listResponse.bodyAsText()).jsonArray
            assertEquals(1, list.size)
            assertEquals("New Name", list[0].jsonObject["name"]?.jsonPrimitive?.content)
        }

    // =========================================================================
    // POST /passkeys/{id}/revoke
    // =========================================================================

    @Test
    fun `POST passkey revoke deletes the credential`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            loginAs(authed)

            // Seed a credential
            relyingParty.queueRegistration()
            authed.post("/t/acme/passkeys/register/start")
            val finishResponse =
                authed.post("/t/acme/passkeys/register/finish") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"credential":{"type":"public-key","id":"cred-002"},"name":"To Revoke"}""")
                }
            val credId =
                Json
                    .parseToJsonElement(finishResponse.bodyAsText())
                    .jsonObject["id"]
                    ?.jsonPrimitive
                    ?.content
                    ?: throw AssertionError("No id in response")

            // Revoke it
            val revokeResponse = authed.post("/t/acme/passkeys/$credId/revoke")

            assertEquals(HttpStatusCode.NoContent, revokeResponse.status)

            // Verify list is empty
            val listResponse = authed.get("/t/acme/passkeys")
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val list = Json.parseToJsonElement(listResponse.bodyAsText()).jsonArray
            assertEquals(0, list.size)
        }

    @Test
    fun `POST passkey revoke of another user's credential returns 404`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            loginAs(authed)

            // Seed a credential for userId=10 (alice)
            relyingParty.queueRegistration()
            authed.post("/t/acme/passkeys/register/start")
            val finishResponse =
                authed.post("/t/acme/passkeys/register/finish") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"credential":{"type":"public-key","id":"cred-003"},"name":"Alice's Key"}""")
                }
            val credId =
                Json
                    .parseToJsonElement(finishResponse.bodyAsText())
                    .jsonObject["id"]
                    ?.jsonPrimitive
                    ?.content
                    ?: throw AssertionError("No id in response")

            // Switch to a different user (userId=99)
            val otherAuthed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            otherAuthed.post("/test-portal-login-user-99")

            // Attempt to revoke alice's credential as user 99
            val revokeResponse = otherAuthed.post("/t/acme/passkeys/$credId/revoke")

            assertEquals(HttpStatusCode.NotFound, revokeResponse.status)

            // Verify credential still exists for alice
            val aliceListResponse = authed.get("/t/acme/passkeys")
            val list = Json.parseToJsonElement(aliceListResponse.bodyAsText()).jsonArray
            assertEquals(1, list.size)
        }

    // =========================================================================
    // Helpers
    // =========================================================================

    private suspend fun loginAs(client: io.ktor.client.HttpClient) {
        client.post("/test-portal-login")
    }

    private fun io.ktor.server.application.Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<PortalSession>("KOTAUTH_PORTAL") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            post("/test-portal-login") {
                call.sessions.set(
                    PortalSession(
                        userId = 10,
                        tenantId = 1,
                        tenantSlug = "acme",
                        username = "alice",
                    ),
                )
                call.respond(HttpStatusCode.OK, "session set")
            }
            post("/test-portal-login-user-99") {
                call.sessions.set(
                    PortalSession(
                        userId = 99,
                        tenantId = 1,
                        tenantSlug = "acme",
                        username = "bob",
                    ),
                )
                call.respond(HttpStatusCode.OK, "session set")
            }
            passkeyPortalRoutes(
                webAuthnService = buildWebAuthnService(),
                encryptionService = encryptionService,
                tenantRepository = tenantRepo,
                userRepository = userRepo,
            )
        }
    }
}
