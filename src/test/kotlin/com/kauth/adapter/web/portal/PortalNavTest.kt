package com.kauth.adapter.web.portal

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AccountSelfService
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * Verifies the portal nav Security parent group renders active state correctly.
 *
 * Checks that visiting any security child page highlights the Security group
 * and that unrelated pages do not trigger the group-active class.
 */
class PortalNavTest {
    private val encryptionService = EncryptionService("test-secret-key")
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val sessionRepo = FakeSessionRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
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

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        auditLogPort.clear()
        tenantRepo.add(tenant)
        userRepo.add(user)
    }

    private fun buildSelfService() =
        AccountSelfService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            emailPort = FakeEmailPort(),
            emailScope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun Application.installWithSession() {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<PortalSession>("KOTAUTH_PORTAL") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            get("/t/{slug}/account/test-set-session") {
                val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                call.sessions.set(
                    PortalSession(
                        userId = 10,
                        tenantId = 1,
                        tenantSlug = slug,
                        username = "alice",
                    ),
                )
                call.respond(HttpStatusCode.OK, "ok")
            }
            portalRoutes(
                accountSelfService = buildSelfService(),
                tenantRepository = tenantRepo,
                encryptionService = encryptionService,
                translationPort = EnglishOnlyTranslation(),
            )
        }
    }

    /** Extract the KOTAUTH_PORTAL cookie value from a set-session response. */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.sessionCookie(): String {
        val noFollow = createClient { followRedirects = false }
        val loginResponse = noFollow.get("/t/acme/account/test-set-session")
        return loginResponse.headers
            .getAll("Set-Cookie")
            ?.firstOrNull { it.startsWith("KOTAUTH_PORTAL=") }
            ?.substringBefore(";")
            ?: error("Session cookie not set")
    }

    @Test
    fun `security parent renders active when MFA child is active`() =
        testApplication {
            application { installWithSession() }
            val cookie = sessionCookie()

            val resp = client.get("/t/acme/account/mfa") { header("Cookie", cookie) }
            val html = resp.bodyAsText()

            assertContains(html, "portal-nav__group--active")
            assertContains(html, "Two-Factor Auth")
            assertContains(html, "Passkeys")
        }

    @Test
    fun `security parent renders active when passkeys child is active`() =
        testApplication {
            application { installWithSession() }
            val cookie = sessionCookie()

            val resp = client.get("/t/acme/account/passkeys") { header("Cookie", cookie) }
            val html = resp.bodyAsText()

            assertContains(html, "portal-nav__group--active")
        }

    @Test
    fun `security parent renders active when security overview child is active`() =
        testApplication {
            application { installWithSession() }
            val cookie = sessionCookie()

            val resp = client.get("/t/acme/account/security") { header("Cookie", cookie) }
            val html = resp.bodyAsText()

            assertContains(html, "portal-nav__group--active")
        }

    @Test
    fun `security group is not active on profile page`() =
        testApplication {
            application { installWithSession() }
            val cookie = sessionCookie()

            val resp = client.get("/t/acme/account/profile") { header("Cookie", cookie) }
            val html = resp.bodyAsText()

            assertFalse(html.contains("portal-nav__group--active"), "Profile page must not activate Security group")
        }
}
