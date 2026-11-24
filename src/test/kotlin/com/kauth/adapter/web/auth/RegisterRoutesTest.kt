package com.kauth.adapter.web.auth

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.IdentifierCollisionCheck
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.UserIdentifierResolver
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegisterRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val tokenPort = FakeTokenPort()
    private val limiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)

    private fun tenant(registrationEnabled: Boolean) =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            registrationEnabled = registrationEnabled,
        )

    private fun ApplicationTestBuilder.installApp(registrationEnabled: Boolean) {
        tenantRepo.clear()
        userRepo.clear()
        tenantRepo.add(tenant(registrationEnabled))
        application {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(
                    authService =
                        AuthService(
                            userRepository = userRepo,
                            tenantRepository = tenantRepo,
                            passwordHasher = hasher,
                            auditLog = auditLog,
                            identifierResolver = UserIdentifierResolver(userRepo),
                            collisionCheck = IdentifierCollisionCheck(userRepo),
                        ),
                    oauthService =
                        OAuthService(
                            tenantRepository = tenantRepo,
                            userRepository = userRepo,
                            applicationRepository = appRepo,
                            sessionRepository = sessionRepo,
                            authCodeRepository = FakeAuthorizationCodeRepository(),
                            tokenPort = tokenPort,
                            passwordHasher = hasher,
                            auditLog = auditLog,
                        ),
                    tenantRepository = tenantRepo,
                    loginRateLimiter = limiter,
                    registerRateLimiter = limiter,
                    tokenRateLimiter = limiter,
                    credentialFlowService = mockk<CredentialFlowService>(relaxed = true),
                    mfaService = mockk<MfaService>(relaxed = true),
                    encryptionService = EncryptionService("test-secret-key"),
                    translationPort = EnglishOnlyTranslation(),
                )
            }
        }
    }

    private suspend fun ApplicationTestBuilder.submitRegistration() =
        client.submitForm(
            url = "/t/acme/register",
            formParameters =
                Parameters.build {
                    append("username", "mallory")
                    append("email", "mallory@example.com")
                    append("fullName", "Mallory")
                    append("password", "Str0ng-Passw0rd!")
                    append("confirmPassword", "Str0ng-Passw0rd!")
                },
        )

    @Test
    fun `GET register on a closed workspace shows the closed page, not the form`() =
        testApplication {
            installApp(registrationEnabled = false)

            val response = client.get("/t/acme/register")

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Registration is closed"))
            assertFalse(body.contains("name=\"password\""), "the sign-up form must not render")
            assertTrue(body.contains("/t/acme/account/login"))
        }

    @Test
    fun `GET register on an open workspace still renders the form`() =
        testApplication {
            installApp(registrationEnabled = true)

            val response = client.get("/t/acme/register")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("name=\"password\""))
        }

    @Test
    fun `POST register on a closed workspace creates no user`() =
        testApplication {
            installApp(registrationEnabled = false)

            val response = submitRegistration()

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(null, userRepo.findByUsername(TenantId(1), "mallory"))
        }
}
