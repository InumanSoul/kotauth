package com.kauth.adapter.web.portal

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import com.kauth.domain.service.AccountSelfService
import com.kauth.domain.service.WebAuthnService
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
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
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PasskeyManagementPageTest {
    private val encryptionService = EncryptionService("test-secret-key-32chars-long-xxxx")
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val sessionRepo = FakeSessionRepository()
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

    private val alice =
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

    private fun buildSelfService() =
        AccountSelfService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLog,
            emailPort = FakeEmailPort(),
            emailScope = CoroutineScope(Dispatchers.Unconfined),
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        credentialRepo.clear()
        tenantRepo.add(tenant)
        userRepo.add(alice)
    }

    @Test
    fun `GET account passkeys renders enrollment list`() =
        testApplication {
            application { installTestApp() }
            val client = createClient { install(HttpCookies) }

            credentialRepo.save(
                WebAuthnCredential(
                    userId = UserId(10),
                    tenantId = TenantId(1),
                    credentialId = "cred-iphone",
                    publicKeyCose = ByteArray(32),
                    signCounter = 0L,
                    aaguid = UUID.randomUUID(),
                    transports = listOf("internal"),
                    name = "iPhone",
                    backupEligible = true,
                    backupState = false,
                    createdAt = Instant.now(),
                    lastUsedAt = null,
                ),
            )
            credentialRepo.save(
                WebAuthnCredential(
                    userId = UserId(10),
                    tenantId = TenantId(1),
                    credentialId = "cred-yubikey",
                    publicKeyCose = ByteArray(32),
                    signCounter = 0L,
                    aaguid = UUID.randomUUID(),
                    transports = listOf("usb"),
                    name = "YubiKey 5",
                    backupEligible = false,
                    backupState = false,
                    createdAt = Instant.now(),
                    lastUsedAt = null,
                ),
            )

            client.post("/test-portal-login")
            val response = client.get("/t/acme/account/passkeys")

            assertEquals(HttpStatusCode.OK, response.status)
            val html = response.bodyAsText()
            assertContains(html, "Passkeys")
            assertContains(html, "Add a passkey")
            assertContains(html, "iPhone")
            assertContains(html, "YubiKey 5")
        }

    @Test
    fun `GET account passkeys shows empty state when no credentials`() =
        testApplication {
            application { installTestApp() }
            val client = createClient { install(HttpCookies) }

            client.post("/test-portal-login")
            val response = client.get("/t/acme/account/passkeys")

            assertEquals(HttpStatusCode.OK, response.status)
            val html = response.bodyAsText()
            assertContains(html, "You have no passkeys enrolled yet")
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
            portalRoutes(
                accountSelfService = buildSelfService(),
                tenantRepository = tenantRepo,
                encryptionService = encryptionService,
                translationPort = EnglishOnlyTranslation(),
                webAuthnService = buildWebAuthnService(),
            )
        }
    }
}
