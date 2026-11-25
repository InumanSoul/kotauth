package com.kauth.adapter.web.portal

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AccountSelfService
import com.kauth.domain.service.UsernameChangeService
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortalUsernameChangeTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val auditLog = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()

    private val usernameChangeService =
        UsernameChangeService(
            users = userRepo,
            tenants = tenantRepo,
            auditLog = auditLog,
            attemptLimiter = InMemoryRateLimiter(maxRequests = 10, windowSeconds = 3600),
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        auditLog.clear()
        workspace(usernameChangeEnabled = true)
        userRepo.add(
            User(
                id = UserId(10),
                tenantId = TenantId(1),
                username = "ana.k3xm7p",
                email = "ana@acme.dev",
                fullName = "Ana",
                passwordHash = hasher.hash("secret"),
                enabled = true,
            ),
        )
    }

    @Test
    fun `a user renames themselves and the page then shows the cooldown`() =
        testApplication {
            application { installTestApp() }
            val client = signedIn()

            val before = client.get("/t/acme/account/profile").bodyAsText()
            assertContains(before, "action=\"/t/acme/account/username\"")

            val response = client.submitForm("/t/acme/account/username", Parameters.build { append("username", "ana") })

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("/t/acme/account/profile?saved=username", response.headers["Location"])
            assertEquals("ana", userRepo.findById(UserId(10), TenantId(1))?.username)
            assertTrue(auditLog.hasEvent(AuditEventType.USER_USERNAME_CHANGED))
            val after = client.get("/t/acme/account/profile").bodyAsText()
            assertContains(after, "@ana<", message = "the session carries the new username")
            assertContains(after, "You can change your username again on")
        }

    @Test
    fun `a taken username comes back as an error and changes nothing`() =
        testApplication {
            application { installTestApp() }
            userRepo.add(
                User(
                    id = UserId(11),
                    tenantId = TenantId(1),
                    username = "bob",
                    email = "bob@acme.dev",
                    fullName = "Bob",
                    passwordHash = "x",
                ),
            )
            val client = signedIn()

            val response = client.submitForm("/t/acme/account/username", Parameters.build { append("username", "bob") })

            assertContains(response.headers["Location"].orEmpty(), "error=")
            assertEquals("ana.k3xm7p", userRepo.findById(UserId(10), TenantId(1))?.username)
        }

    @Test
    fun `with the switch off the page shows no form and a post is refused`() =
        testApplication {
            application { installTestApp() }
            workspace(usernameChangeEnabled = false)
            val client = signedIn()

            val page = client.get("/t/acme/account/profile").bodyAsText()
            assertFalse(page.contains("/t/acme/account/username"))
            assertContains(page, EnglishStrings.PORTAL_PROFILE_USERNAME_HINT)

            val response = client.submitForm("/t/acme/account/username", Parameters.build { append("username", "ana") })
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("ana.k3xm7p", userRepo.findById(UserId(10), TenantId(1))?.username)
        }

    @Test
    fun `an administrator impersonating the user cannot rename them`() =
        testApplication {
            application { installTestApp() }
            val client = signedIn(impersonated = true)

            val response = client.submitForm("/t/acme/account/username", Parameters.build { append("username", "ana") })

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("ana.k3xm7p", userRepo.findById(UserId(10), TenantId(1))?.username)
        }

    private fun workspace(usernameChangeEnabled: Boolean) {
        tenantRepo.add(
            Tenant(
                id = TenantId(1),
                slug = "acme",
                displayName = "Acme Corp",
                issuerUrl = null,
                theme = TenantTheme.DEFAULT,
                securityConfig = SecurityConfig(usernameChangeEnabled = usernameChangeEnabled),
            ),
        )
    }

    private suspend fun ApplicationTestBuilder.signedIn(impersonated: Boolean = false): HttpClient {
        val client =
            createClient {
                install(HttpCookies)
                followRedirects = false
            }
        client.post(if (impersonated) "/test-impersonated-login" else "/test-portal-login")
        return client
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
                    PortalSession(userId = 10, tenantId = 1, tenantSlug = "acme", username = "ana.k3xm7p"),
                )
                call.respond(HttpStatusCode.OK, "session set")
            }
            post("/test-impersonated-login") {
                call.sessions.set(
                    PortalSession(
                        userId = 10,
                        tenantId = 1,
                        tenantSlug = "acme",
                        username = "ana.k3xm7p",
                        impersonatorAdminUserId = 1,
                        impersonatorAdminUsername = "admin",
                        impersonatorAdminSessionId = 99,
                    ),
                )
                call.respond(HttpStatusCode.OK, "session set")
            }
            portalRoutes(
                accountSelfService =
                    AccountSelfService(
                        userRepository = userRepo,
                        tenantRepository = tenantRepo,
                        sessionRepository = FakeSessionRepository(),
                        passwordHasher = hasher,
                        auditLog = auditLog,
                        emailPort = FakeEmailPort(),
                        emailScope = CoroutineScope(Dispatchers.Unconfined),
                    ),
                tenantRepository = tenantRepo,
                encryptionService = EncryptionService("test-secret-key-32chars-long-xxxx"),
                translationPort = EnglishOnlyTranslation(),
                usernameChangeService = usernameChangeService,
            )
        }
    }
}
