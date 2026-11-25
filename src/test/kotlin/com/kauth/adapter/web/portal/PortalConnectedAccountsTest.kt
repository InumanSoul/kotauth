package com.kauth.adapter.web.portal

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AccountSelfService
import com.kauth.domain.service.LinkedIdentityService
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeSocialAccountRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortalConnectedAccountsTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val links = FakeSocialAccountRepository()
    private val providers = FakeIdentityProviderRepository()
    private val auditLog = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()
    private val oriana = ProviderKey.of("oriana")!!

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(passwordLoginEnabled = false),
        )

    private val linkedIdentityService =
        LinkedIdentityService(
            socialAccounts = links,
            users = userRepo,
            tenants = tenantRepo,
            identityProviders = providers,
            passkeys = FakeWebAuthnCredentialRepository(),
            auditLog = auditLog,
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        links.clear()
        providers.clear()
        auditLog.clear()
        tenantRepo.add(tenant)
        userRepo.add(
            User(
                id = UserId(10),
                tenantId = TenantId(1),
                username = "alice",
                email = "alice@acme.dev",
                fullName = "Alice",
                passwordHash = User.SENTINEL_PASSWORD_HASH,
                enabled = true,
            ),
        )
        providers.add(
            IdentityProvider(tenantId = TenantId(1), provider = ProviderKey.GOOGLE, clientId = "c", clientSecret = "s"),
        )
        providers.add(IdentityProvider(tenantId = TenantId(1), provider = oriana, clientId = "c", clientSecret = "s"))
        link(ProviderKey.GOOGLE)
    }

    @Test
    fun `the only way to sign in shows a disabled disconnect, and a forged post is refused`() =
        testApplication {
            application { installTestApp() }
            val client = signedIn()

            val page = client.get("/t/acme/account/profile").bodyAsText()
            assertContains(page, EnglishStrings.CONNECTED_ACCOUNTS_ONLY_WAY)
            assertFalse(page.contains("/connected-accounts/google/disconnect"))

            val response = client.post("/t/acme/account/connected-accounts/google/disconnect")
            assertEquals(HttpStatusCode.Found, response.status)
            assertContains(response.headers["Location"].orEmpty(), "error=")
            assertEquals(1, links.findByUserId(UserId(10)).size)
        }

    @Test
    fun `a user with another account linked disconnects one`() =
        testApplication {
            application { installTestApp() }
            link(oriana)
            val client = signedIn()

            val page = client.get("/t/acme/account/profile").bodyAsText()
            assertContains(page, "/t/acme/account/connected-accounts/google/disconnect")

            val response = client.post("/t/acme/account/connected-accounts/google/disconnect")
            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("/t/acme/account/profile?saved=disconnected", response.headers["Location"])
            assertEquals(listOf(oriana), links.findByUserId(UserId(10)).map { it.provider })
            assertTrue(auditLog.hasEvent(AuditEventType.IDENTITY_UNLINKED))
        }

    @Test
    fun `an administrator impersonating the user cannot disconnect anything`() =
        testApplication {
            application { installTestApp() }
            link(oriana)
            val client = signedIn(impersonated = true)

            val response = client.post("/t/acme/account/connected-accounts/google/disconnect")

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(2, links.findByUserId(UserId(10)).size)
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.signedIn(
        impersonated: Boolean = false,
    ): HttpClient {
        val client =
            createClient {
                install(HttpCookies)
                followRedirects = false
            }
        client.post(if (impersonated) "/test-impersonated-login" else "/test-portal-login")
        return client
    }

    private fun link(key: ProviderKey) =
        links.save(
            SocialAccount(
                userId = UserId(10),
                tenantId = TenantId(1),
                provider = key,
                providerUserId = "${key.value}-alice",
                providerEmail = "alice@acme.dev",
                providerName = "Alice",
            ),
        )

    private fun io.ktor.server.application.Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<PortalSession>("KOTAUTH_PORTAL") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            post("/test-portal-login") {
                call.sessions.set(PortalSession(userId = 10, tenantId = 1, tenantSlug = "acme", username = "alice"))
                call.respond(HttpStatusCode.OK, "session set")
            }
            post("/test-impersonated-login") {
                call.sessions.set(
                    PortalSession(
                        userId = 10,
                        tenantId = 1,
                        tenantSlug = "acme",
                        username = "alice",
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
                socialAccountRepository = links,
                linkedIdentityService = linkedIdentityService,
            )
        }
    }
}
