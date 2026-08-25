package com.kauth.adapter.web.auth

import com.kauth.config.StaticSocialProviderResolver
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SocialLoginService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeSocialAccountRepository
import com.kauth.fakes.FakeSocialProviderPort
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HTTP integration tests for the social-login state parameter.
 *
 * The signed state carries `provider|slug|csrfNonce|timestampMillis|oauthParamsB64`.
 * These tests forge states directly with [EncryptionService.signCookie] so the
 * callback guard can be exercised without a real provider round-trip.
 */
class SocialLoginRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val authCodeRepo = FakeAuthorizationCodeRepository()
    private val sessionRepo = FakeSessionRepository()
    private val socialAccountRepo = FakeSocialAccountRepository()
    private val idpRepo = FakeIdentityProviderRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val tokenPort = FakeTokenPort()

    private val loginLimiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val registerLimiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val tokenLimiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)

    private val credentialFlowService = mockk<CredentialFlowService>(relaxed = true)

    private val googleAdapter = FakeSocialProviderPort(ProviderKey.GOOGLE)

    private val encryptionService = EncryptionService("test-secret-key")

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private fun buildAuthService() =
        AuthService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLog,
            sessionRepository = sessionRepo,
        )

    private fun buildOAuthService() =
        OAuthService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            applicationRepository = appRepo,
            sessionRepository = sessionRepo,
            authCodeRepository = authCodeRepo,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLog,
        )

    private fun buildSocialLoginService() =
        SocialLoginService(
            identityProviderRepository = idpRepo,
            socialAccountRepository = socialAccountRepo,
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = sessionRepo,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLog,
            providerResolver = StaticSocialProviderResolver(mapOf(ProviderKey.GOOGLE to googleAdapter)),
        )

    private fun resetFixtures() {
        tenantRepo.clear()
        userRepo.clear()
        appRepo.clear()
        authCodeRepo.clear()
        sessionRepo.clear()
        socialAccountRepo.clear()
        idpRepo.clear()
        auditLog.clear()
        tokenPort.reset()
        googleAdapter.clear()
        tenantRepo.add(tenant)
        idpRepo.seed(TenantId(1), "google")
        // The provider exchange fails, so a state that passes the guard still ends on
        // the login page — that is what separates "rejected by the guard" from "let through".
        googleAdapter.shouldFail = true
    }

    private fun statePayload(
        provider: String,
        slug: String,
        timestampMillis: Long,
        oauthParamsB64: String = "",
    ): String {
        val csrfNonce =
            UUID
                .randomUUID()
                .toString()
        return "$provider|$slug|$csrfNonce|$timestampMillis|$oauthParamsB64"
    }

    private fun signedState(payload: String) = encryptionService.signCookie(payload).encodeURLParameter()

    private fun ApplicationTestBuilder.installSocialRoutes() {
        application {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(
                    authService = buildAuthService(),
                    oauthService = buildOAuthService(),
                    tenantRepository = tenantRepo,
                    loginRateLimiter = loginLimiter,
                    registerRateLimiter = registerLimiter,
                    tokenRateLimiter = tokenLimiter,
                    credentialFlowService = credentialFlowService,
                    socialLoginService = buildSocialLoginService(),
                    identityProviderRepository = idpRepo,
                    encryptionService = encryptionService,
                    translationPort = EnglishOnlyTranslation(),
                )
            }
        }
    }

    @Test
    fun `a callback whose state is older than the max age is rejected`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // state stamped 6 minutes ago — beyond the 300_000 ms window
            val stale = System.currentTimeMillis() - 360_000
            val payload = statePayload("google", "acme", stale)
            val response =
                client.get(
                    "/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("State mismatch"),
                "A stale state must be stopped by the state guard, not somewhere downstream",
            )
        }

    @Test
    fun `a callback whose state is within the max age passes the state check`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            val fresh = System.currentTimeMillis()
            val payload = statePayload("google", "acme", fresh)
            val response =
                client.get(
                    "/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}",
                )

            // The state check passes; the flow then fails further on at the provider exchange,
            // which is what proves the request got past the guard rather than being stopped by it.
            assertFalse(response.bodyAsText().contains("State mismatch"))
        }

    @Test
    fun `a redirect for a well-formed key with no compiled-in adapter is rejected as unsupported`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // "okta" satisfies ^[a-z0-9-]{1,32}$, so ProviderKey.of parses it. Only the RESERVED
            // membership check stops it. Without that check the request reaches buildRedirectUrl,
            // finds no configured provider and renders the login page — also a 400, different cause.
            val response = client.get("/t/acme/auth/social/okta/redirect")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("unsupported_provider"),
                "A key with no adapter must be refused by the provider guard, not by a downstream lookup",
            )
        }

    @Test
    fun `a callback for a well-formed key with no compiled-in adapter is rejected as unsupported`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // Signed state that agrees with the URL segment, so nothing downstream of the provider
            // guard has a reason to reject: drop the guard and this reaches ProviderNotConfigured.
            val payload = statePayload("okta", "acme", System.currentTimeMillis())
            val response =
                client.get("/t/acme/auth/social/okta/callback?code=abc&state=${signedState(payload)}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("unsupported_provider"),
                "A key with no adapter must be refused by the provider guard, not by a downstream lookup",
            )
        }

    @Test
    fun `a callback whose state names a different provider is rejected`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            val payload = statePayload("github", "acme", System.currentTimeMillis())
            val response =
                client.get(
                    "/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            // Naming the guard, not just the status: the provider comes from the URL segment,
            // not from the state, so a dropped mismatch check would still 400 downstream at the
            // provider exchange — the same code by a different cause.
            assertTrue(response.bodyAsText().contains("State mismatch"))
        }
}
