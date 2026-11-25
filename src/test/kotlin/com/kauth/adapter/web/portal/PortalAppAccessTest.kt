package com.kauth.adapter.web.portal

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AccountSelfService
import com.kauth.domain.service.ConsentService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeConsentRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalAppAccessTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val consents = FakeConsentRepository()
    private val auditLog = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()
    private val consentService = ConsentService(consents, appRepo, sessionRepo, userRepo, auditLog)
    private val partner =
        Application(
            id = ApplicationId(5),
            tenantId = TenantId(1),
            clientId = "partner-app",
            name = "Partner Reports",
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://partner.example.com/cb"),
            grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
            consentRequired = true,
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        appRepo.clear()
        sessionRepo.clear()
        consents.clear()
        auditLog.clear()
        tenantRepo.add(
            Tenant(
                id = TenantId(1),
                slug = "acme",
                displayName = "Acme",
                issuerUrl = null,
                theme = TenantTheme.DEFAULT,
            ),
        )
        userRepo.add(
            User(
                id = UserId(10),
                tenantId = TenantId(1),
                username = "ana",
                email = "ana@acme.dev",
                fullName = "Ana",
                passwordHash = hasher.hash("secret"),
                enabled = true,
            ),
        )
        appRepo.add(partner)
        consents.save(TenantId(1), UserId(10), partner.id, setOf("openid", "email"), Instant.now())
    }

    @Test
    fun `the page lists what each application may do, in the consent screen's words`() =
        testApplication {
            application { installTestApp() }

            val page = signedIn().get("/t/acme/account/apps").bodyAsText()

            assertContains(page, "Partner Reports")
            assertContains(page, "See your email address")
            assertContains(page, "action=\"/t/acme/account/apps/5/revoke\"")
        }

    @Test
    fun `removing access deletes the grant and signs the app out`() =
        testApplication {
            application { installTestApp() }
            val appSession =
                sessionRepo.save(
                    Session(
                        tenantId = TenantId(1),
                        userId = UserId(10),
                        clientId = partner.id,
                        accessTokenHash = "a",
                        refreshTokenHash = "r",
                        expiresAt = Instant.now().plusSeconds(3600),
                    ),
                )

            val response = signedIn().post("/t/acme/account/apps/5/revoke")

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("/t/acme/account/apps?saved=revoked", response.headers["Location"])
            assertNull(consents.find(TenantId(1), UserId(10), partner.id))
            assertTrue(sessionRepo.findById(appSession.id!!)?.revokedAt != null)
            assertTrue(auditLog.hasEvent(AuditEventType.CONSENT_REVOKED))
        }

    @Test
    fun `an administrator impersonating the user cannot remove access`() =
        testApplication {
            application { installTestApp() }
            val client = signedIn(impersonated = true)

            assertFalse(client.get("/t/acme/account/apps").bodyAsText().contains("/apps/5/revoke\""))
            assertEquals(HttpStatusCode.Forbidden, client.post("/t/acme/account/apps/5/revoke").status)
            assertEquals(setOf("openid", "email"), consents.find(TenantId(1), UserId(10), partner.id)?.scopes)
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
                    PortalSession(userId = 10, tenantId = 1, tenantSlug = "acme", username = "ana"),
                )
                call.respond(HttpStatusCode.OK, "session set")
            }
            post("/test-impersonated-login") {
                call.sessions.set(
                    PortalSession(
                        userId = 10,
                        tenantId = 1,
                        tenantSlug = "acme",
                        username = "ana",
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
                        sessionRepository = sessionRepo,
                        passwordHasher = hasher,
                        auditLog = auditLog,
                        emailPort = FakeEmailPort(),
                        emailScope = CoroutineScope(Dispatchers.Unconfined),
                    ),
                tenantRepository = tenantRepo,
                encryptionService = EncryptionService("test-secret-key-32chars-long-xxxx"),
                translationPort = EnglishOnlyTranslation(),
                consentService = consentService,
            )
        }
    }
}
