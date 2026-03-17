package com.kauth

import com.kauth.adapter.email.SmtpEmailAdapter
import com.kauth.adapter.persistence.PostgresApiKeyRepository
import com.kauth.adapter.persistence.PostgresApplicationRepository
import com.kauth.adapter.persistence.PostgresAuditLogAdapter
import com.kauth.adapter.persistence.PostgresAuditLogRepository
import com.kauth.adapter.persistence.PostgresAuthorizationCodeRepository
import com.kauth.adapter.persistence.PostgresEmailVerificationTokenRepository
import com.kauth.adapter.persistence.PostgresGroupRepository
import com.kauth.adapter.persistence.PostgresIdentityProviderRepository
import com.kauth.adapter.persistence.PostgresMfaRepository
import com.kauth.adapter.persistence.PostgresPasswordPolicyAdapter
import com.kauth.adapter.persistence.PostgresPasswordResetTokenRepository
import com.kauth.adapter.persistence.PostgresRoleRepository
import com.kauth.adapter.persistence.PostgresSessionRepository
import com.kauth.adapter.persistence.PostgresSocialAccountRepository
import com.kauth.adapter.persistence.PostgresTenantKeyRepository
import com.kauth.adapter.persistence.PostgresTenantRepository
import com.kauth.adapter.persistence.PostgresUserRepository
import com.kauth.adapter.social.GitHubOAuthAdapter
import com.kauth.adapter.social.GoogleOAuthAdapter
import com.kauth.adapter.token.BcryptPasswordHasher
import com.kauth.adapter.token.JwtTokenAdapter
import com.kauth.adapter.web.admin.AdminSession
import com.kauth.adapter.web.admin.AdminView
import com.kauth.adapter.web.admin.adminRoutes
import com.kauth.adapter.web.auth.authRoutes
import com.kauth.adapter.web.portal.PortalSession
import com.kauth.adapter.web.portal.portalRoutes
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.SocialLoginService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.DatabaseFactory
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import com.kauth.infrastructure.PortalClientProvisioning
import com.kauth.infrastructure.RateLimiter
import com.kauth.adapter.web.api.apiRoutes
import com.kauth.adapter.web.healthRoutes
import io.ktor.server.auth.*
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import org.slf4j.event.Level
import kotlin.system.exitProcess

/**
 * KotAuth — Composition Root (Phase 2)
 *
 * Dependency flow (outermost → innermost):
 *   HTTP (Ktor routes)
 *     → AuthService / OAuthService (domain use cases)
 *       → Repository ports   ← Postgres adapters
 *       → TokenPort          ← JwtTokenAdapter (RS256)
 *       → PasswordHasher     ← BcryptPasswordHasher
 *       → AuditLogPort       ← PostgresAuditLogAdapter
 *
 * Startup sequence:
 *   1. Validate environment (fail fast on bad config)
 *   2. Run Flyway migrations
 *   3. Provision RSA keys for any tenant that lacks one
 *   4. Start Ktor server
 */
fun main() {
    // -------------------------------------------------------------------------
    // Phase 0: Startup validation — fail fast on misconfigured environment
    // -------------------------------------------------------------------------
    val baseUrl = System.getenv("KAUTH_BASE_URL")
    if (baseUrl.isNullOrBlank()) {
        System.err.println("""
            ┌──────────────────────────────────────────────────────────┐
            │  FATAL: KAUTH_BASE_URL environment variable is not set.  │
            │                                                          │
            │  This is required to generate correct issuer URLs in     │
            │  OIDC tokens and discovery documents.                    │
            │                                                          │
            │  Example: KAUTH_BASE_URL=https://auth.yourdomain.com     │
            │  Local:   KAUTH_BASE_URL=http://localhost:8080           │
            └──────────────────────────────────────────────────────────┘
        """.trimIndent())
        exitProcess(1)
    }

    // -------------------------------------------------------------------------
    // HTTPS enforcement — gates Phase 4 identity federation requirements
    // -------------------------------------------------------------------------
    // OAuth2 providers (Google, GitHub, Microsoft) require HTTPS redirect URIs.
    // Session cookies need secure transport. OIDC discovery documents must be
    // served over HTTPS. None of this works safely over plain HTTP.
    //
    // TLS termination is handled by the reverse proxy (nginx, Caddy, etc.),
    // not by Ktor — but we validate the configured base URL here at startup.
    val isHttps      = baseUrl.startsWith("https://")
    val isLocalhost  = baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")

    val env = System.getenv("KAUTH_ENV") ?: "development"

    if (!isHttps) {
        when {
            env == "production" -> {
                System.err.println("""
                    ┌──────────────────────────────────────────────────────────────────┐
                    │  FATAL: KAUTH_BASE_URL must use HTTPS in production mode.        │
                    │                                                                  │
                    │  OAuth2 providers require HTTPS redirect URIs.                  │
                    │  Session cookies require a secure transport layer.              │
                    │  OIDC discovery documents must be served over HTTPS.            │
                    │                                                                  │
                    │  Set up TLS on your reverse proxy (nginx, Caddy, etc.) and      │
                    │  update KAUTH_BASE_URL to use https://.                         │
                    │                                                                  │
                    │  Current value: $baseUrl
                    └──────────────────────────────────────────────────────────────────┘
                """.trimIndent())
                exitProcess(1)
            }
            !isLocalhost -> {
                System.err.println("""
                    [WARN] KAUTH_BASE_URL is not HTTPS and does not appear to be localhost.
                           Current value: $baseUrl
                           This will break identity federation — OAuth2 providers (Google,
                           GitHub, Microsoft) reject non-HTTPS redirect URIs.
                           Ensure your reverse proxy handles TLS before exposing this to
                           any public or staging environment.
                """.trimIndent())
            }
            else -> {
                System.err.println("[DEV]  KAUTH_BASE_URL is HTTP on localhost — acceptable for local development only.")
            }
        }
    }

    if (env == "production") {
        val legacySecret = System.getenv("JWT_SECRET")
        if (!legacySecret.isNullOrBlank() && legacySecret == "secret-key-12345") {
            System.err.println("FATAL: JWT_SECRET is set to the insecure default value in production mode. Refusing to start.")
            exitProcess(1)
        }
    }

    // Phase 3b: KAUTH_SECRET_KEY — used for AES-256-GCM SMTP password encryption
    // and portal session signing. Non-fatal: app starts without it, but SMTP
    // config will be unavailable and portal sessions won't survive restarts.
    if (!EncryptionService.isAvailable) {
        System.err.println("""
            [WARN] KAUTH_SECRET_KEY is not set.
                   SMTP passwords cannot be stored and portal sessions are ephemeral.
                   Set this env var to a random 32+ char string for production use.
        """.trimIndent())
    }

    // -------------------------------------------------------------------------
    // Database + migrations
    // -------------------------------------------------------------------------
    DatabaseFactory.init(
        url      = System.getenv("DB_URL")      ?: "jdbc:postgresql://localhost:5432/kauth_db",
        user     = System.getenv("DB_USER")     ?: "postgres",
        password = System.getenv("DB_PASSWORD") ?: "password"
    )

    // -------------------------------------------------------------------------
    // Repositories (constructed once, shared across all requests)
    // -------------------------------------------------------------------------
    val userRepository        = PostgresUserRepository()
    val tenantRepository      = PostgresTenantRepository()
    val applicationRepository = PostgresApplicationRepository()
    val tenantKeyRepository   = PostgresTenantKeyRepository()
    val sessionRepository     = PostgresSessionRepository()
    val authCodeRepository    = PostgresAuthorizationCodeRepository()
    val auditLogAdapter       = PostgresAuditLogAdapter()
    val auditLogRepository    = PostgresAuditLogRepository()
    val passwordHasher        = BcryptPasswordHasher()
    // Phase 3b: email verification + password reset token repositories
    val evTokenRepository     = PostgresEmailVerificationTokenRepository()
    val prTokenRepository     = PostgresPasswordResetTokenRepository()
    // Phase 3c: roles & groups
    val roleRepository        = PostgresRoleRepository()
    val groupRepository       = PostgresGroupRepository()
    val passwordPolicyAdapter = PostgresPasswordPolicyAdapter(passwordHasher)
    // Phase 2: Social Login repositories
    val identityProviderRepository = PostgresIdentityProviderRepository()
    val socialAccountRepository    = PostgresSocialAccountRepository()
    // Phase 3a: API keys
    val apiKeyRepository           = PostgresApiKeyRepository()

    // -------------------------------------------------------------------------
    // RS256 key provisioning — ensure every tenant has a signing key
    // -------------------------------------------------------------------------
    val keyProvisioning = KeyProvisioningService(tenantRepository, tenantKeyRepository)
    keyProvisioning.provisionMissingKeys()

    // -------------------------------------------------------------------------
    // Portal client provisioning — ensure every non-master tenant has the
    // built-in 'kotauth-portal' PUBLIC client with the correct redirect URI.
    // Run after key provisioning so tenant list is stable.
    // -------------------------------------------------------------------------
    val portalClientProvisioning = PortalClientProvisioning(
        tenantRepository      = tenantRepository,
        applicationRepository = applicationRepository,
        baseUrl               = baseUrl
    )
    portalClientProvisioning.provisionRedirectUris()

    // -------------------------------------------------------------------------
    // Token adapter — RS256, per-tenant key pairs
    // -------------------------------------------------------------------------
    val tokenAdapter = JwtTokenAdapter(
        baseUrl           = baseUrl,
        tenantKeyRepository = tenantKeyRepository
    )

    // -------------------------------------------------------------------------
    // Domain services
    // -------------------------------------------------------------------------

    // Phase 3b: SMTP email adapter + self-service domain service
    val emailAdapter = SmtpEmailAdapter()
    val selfServiceService = UserSelfServiceService(
        userRepository    = userRepository,
        tenantRepository  = tenantRepository,
        sessionRepository = sessionRepository,
        passwordHasher    = passwordHasher,
        auditLog          = auditLogAdapter,
        evTokenRepo       = evTokenRepository,
        prTokenRepo       = prTokenRepository,
        emailPort         = emailAdapter,
        passwordPolicy    = passwordPolicyAdapter
    )

    val authService = AuthService(
        userRepository    = userRepository,
        tenantRepository  = tenantRepository,
        tokenPort         = tokenAdapter,
        passwordHasher    = passwordHasher,
        auditLog          = auditLogAdapter,
        sessionRepository = sessionRepository,
        selfServiceService = selfServiceService,
        passwordPolicy    = passwordPolicyAdapter
    )
    val oauthService = OAuthService(
        tenantRepository      = tenantRepository,
        userRepository        = userRepository,
        applicationRepository = applicationRepository,
        sessionRepository     = sessionRepository,
        authCodeRepository    = authCodeRepository,
        tokenPort             = tokenAdapter,
        passwordHasher        = passwordHasher,
        auditLog              = auditLogAdapter,
        roleRepository        = roleRepository
    )
    val adminService = AdminService(
        tenantRepository      = tenantRepository,
        userRepository        = userRepository,
        applicationRepository = applicationRepository,
        passwordHasher        = passwordHasher,
        auditLog              = auditLogAdapter,
        sessionRepository     = sessionRepository,
        selfServiceService    = selfServiceService,
        passwordPolicy        = passwordPolicyAdapter
    )
    val roleGroupService = RoleGroupService(
        roleRepository        = roleRepository,
        groupRepository       = groupRepository,
        tenantRepository      = tenantRepository,
        userRepository        = userRepository,
        applicationRepository = applicationRepository,
        auditLog              = auditLogAdapter
    )
    // Phase 3c: MFA / TOTP
    val mfaRepository = PostgresMfaRepository()
    val mfaService = MfaService(
        mfaRepository    = mfaRepository,
        userRepository   = userRepository,
        tenantRepository = tenantRepository,
        passwordHasher   = passwordHasher,
        auditLog         = auditLogAdapter
    )

    // Phase 3a: API key service
    val apiKeyService = ApiKeyService(
        apiKeyRepository = apiKeyRepository,
        tenantRepository = tenantRepository
    )

    // Phase 2: Social Login service — wires provider HTTP adapters
    val socialLoginService = SocialLoginService(
        identityProviderRepository = identityProviderRepository,
        socialAccountRepository    = socialAccountRepository,
        userRepository             = userRepository,
        tenantRepository           = tenantRepository,
        sessionRepository          = sessionRepository,
        tokenPort                  = tokenAdapter,
        passwordHasher             = passwordHasher,
        auditLog                   = auditLogAdapter,
        providerAdapters           = mapOf(
            SocialProvider.GOOGLE to GoogleOAuthAdapter(),
            SocialProvider.GITHUB to GitHubOAuthAdapter()
        )
    )

    // -------------------------------------------------------------------------
    // Rate limiters (Phase 0)
    // -------------------------------------------------------------------------
    val loginRateLimiter    = RateLimiter(maxRequests = 5,  windowSeconds = 60)   // 5 attempts / minute per IP
    val registerRateLimiter = RateLimiter(maxRequests = 3,  windowSeconds = 300)  // 3 registrations / 5 min per IP

    // Phase 3b: portal session signing key — derived from KAUTH_SECRET_KEY for persistence
    // across restarts. Random key is used as fallback (sessions are valid only until restart).
    val portalSessionKey: ByteArray = run {
        val secret = System.getenv("KAUTH_SECRET_KEY")
        if (!secret.isNullOrBlank()) {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("portal-session:$secret".toByteArray(Charsets.UTF_8))
        } else {
            ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        }
    }

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(
            authService                = authService,
            oauthService               = oauthService,
            adminService               = adminService,
            roleGroupService           = roleGroupService,
            selfServiceService         = selfServiceService,
            mfaService                 = mfaService,
            mfaRepository              = mfaRepository,
            roleRepository             = roleRepository,
            groupRepository            = groupRepository,
            tenantRepository           = tenantRepository,
            applicationRepository      = applicationRepository,
            userRepository             = userRepository,
            sessionRepository          = sessionRepository,
            auditLogRepository         = auditLogRepository,
            keyProvisioningService     = keyProvisioning,
            loginRateLimiter           = loginRateLimiter,
            registerRateLimiter        = registerRateLimiter,
            portalSessionKey           = portalSessionKey,
            baseUrl                    = baseUrl,
            portalClientProvisioning   = portalClientProvisioning,
            socialLoginService         = socialLoginService,
            identityProviderRepository = identityProviderRepository,
            apiKeyService              = apiKeyService,
            apiKeyRepository           = apiKeyRepository
        )
    }.start(wait = true)
}

fun Application.module(
    authService                : AuthService,
    oauthService               : OAuthService,
    adminService               : AdminService,
    roleGroupService           : RoleGroupService,
    selfServiceService         : UserSelfServiceService,
    mfaService                 : MfaService,
    tenantRepository           : com.kauth.domain.port.TenantRepository,
    applicationRepository      : com.kauth.domain.port.ApplicationRepository,
    userRepository             : com.kauth.domain.port.UserRepository,
    sessionRepository          : com.kauth.domain.port.SessionRepository,
    auditLogRepository         : com.kauth.domain.port.AuditLogRepository,
    keyProvisioningService     : KeyProvisioningService,
    mfaRepository              : com.kauth.domain.port.MfaRepository,
    roleRepository             : com.kauth.domain.port.RoleRepository,
    groupRepository            : com.kauth.domain.port.GroupRepository,
    loginRateLimiter           : RateLimiter,
    registerRateLimiter        : RateLimiter,
    portalSessionKey           : ByteArray,
    baseUrl                    : String,
    portalClientProvisioning   : PortalClientProvisioning,
    socialLoginService         : SocialLoginService? = null,                            // Phase 2
    identityProviderRepository : com.kauth.domain.port.IdentityProviderRepository? = null, // Phase 2
    apiKeyService              : ApiKeyService? = null,                                 // Phase 3a
    apiKeyRepository           : com.kauth.domain.port.ApiKeyRepository? = null         // Phase 3a
) {
    // -------------------------------------------------------------------------
    // Plugins
    // -------------------------------------------------------------------------
    install(ContentNegotiation) { json() }

    // Assign a unique ID to every request and echo it in the X-Request-Id response header.
    // The ID flows into MDC so every log line emitted during a request automatically
    // includes "requestId" — enabling trace reconstruction in any log aggregator.
    install(CallId) {
        generate { java.util.UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }

    // Structured access log: one INFO line per completed request with method, path,
    // status, duration, and MDC fields (requestId, tenantSlug) baked in by the
    // JSON encoder. Health check endpoints are excluded to avoid log noise.
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        // Extract the tenant slug from /t/{slug}/... paths and put it in MDC
        // so every log line within that request is automatically scoped.
        mdc("tenantSlug") { call ->
            val path = call.request.path()
            if (path.startsWith("/t/")) path.split("/").getOrNull(2) else null
        }
        // Skip health probes — they're high-frequency and add no diagnostic value
        filter { call -> !call.request.path().startsWith("/health") }
    }

    // -------------------------------------------------------------------------
    // API key authentication (Phase 3a)
    // Bearer token scheme: Authorization: Bearer kauth_<slug>_<random>
    // Tenant context is extracted from the URL path parameter {tenantSlug} by
    // the route itself and used to scope key lookup.
    // -------------------------------------------------------------------------
    install(Authentication) {
        bearer("api-key") {
            // Realm is informational only — returned in WWW-Authenticate on 401
            realm = "KotAuth REST API"
            // Actual validation happens inside apiRoutes — this provider just
            // extracts the raw token and makes it available via principal.
            // A BearerTokenPrincipal is set if the token is non-blank;
            // the route handler calls apiKeyService.validate() with tenant context.
            authenticate { tokenCredential ->
                // Defer real validation to the route (tenant not available here).
                // Return a simple principal carrying the raw token; routes will
                // call apiKeyService.validate(rawToken, tenantId) themselves.
                if (tokenCredential.token.startsWith("kauth_")) {
                    ApiKeyPrincipal(rawToken = tokenCredential.token)
                } else {
                    null  // Reject anything that doesn't look like our key format
                }
            }
        }
    }

    install(Sessions) {
        cookie<AdminSession>("KOTAUTH_ADMIN") {
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = 3600 * 8  // 8-hour admin session
            // TODO (Phase 5): cookie.secure = true once TLS is enforced
        }
        // Phase 3b: self-service portal session — HMAC-signed with derived key
        cookie<PortalSession>("KOTAUTH_PORTAL") {
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = 3600 * 4  // 4-hour portal session
            transform(SessionTransportTransformerMessageAuthentication(portalSessionKey))
        }
    }

    // -------------------------------------------------------------------------
    // Error boundary
    // -------------------------------------------------------------------------
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception at ${call.request.path()}", cause)
            if (call.request.path().startsWith("/admin")) {
                val session = call.sessions.get<AdminSession>()
                val workspaces = try {
                    tenantRepository.findAll().map { it.slug to it.displayName }
                } catch (_: Exception) {
                    emptyList()
                }
                call.respondHtml(
                    HttpStatusCode.InternalServerError,
                    AdminView.adminErrorPage(
                        message       = cause.message ?: "An unexpected error occurred.",
                        exceptionType = cause::class.qualifiedName,
                        allWorkspaces = workspaces,
                        loggedInAs    = session?.username ?: "—"
                    )
                )
            } else {
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "error" to "server_error",
                    "error_description" to "An unexpected error occurred"
                ))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Routes
    // -------------------------------------------------------------------------
    routing {
        staticResources("/static", "static")

        // Root → master tenant login
        get("/") {
            call.respondRedirect("/t/master/login", permanent = false)
        }

        // Health probes — liveness (/health) + readiness (/health/ready)
        healthRoutes(baseUrl)

        // All tenant auth + OIDC flows (Phase 1–2)
        authRoutes(
            authService                = authService,
            oauthService               = oauthService,
            tenantRepository           = tenantRepository,
            loginRateLimiter           = loginRateLimiter,
            registerRateLimiter        = registerRateLimiter,
            selfServiceService         = selfServiceService,
            mfaService                 = mfaService,
            roleRepository             = roleRepository,
            socialLoginService         = socialLoginService,
            identityProviderRepository = identityProviderRepository,
            baseUrl                    = baseUrl
        )

        // Self-service portal — /t/{slug}/account/* (Phase 4: OAuth-backed login)
        portalRoutes(
            authService        = authService,
            selfServiceService = selfServiceService,
            tenantRepository   = tenantRepository,
            mfaService         = mfaService,
            userRepository     = userRepository,
            oauthService       = oauthService,
            baseUrl            = baseUrl
        )

        // REST API v1 — /t/{tenantSlug}/api/v1/** (Phase 3b)
        if (apiKeyService != null) {
            apiRoutes(
                apiKeyService     = apiKeyService,
                tenantRepository  = tenantRepository,
                userRepository    = userRepository,
                roleRepository    = roleRepository,
                groupRepository   = groupRepository,
                applicationRepository = applicationRepository,
                sessionRepository = sessionRepository,
                auditLogRepository = auditLogRepository,
                roleGroupService  = roleGroupService,
                adminService      = adminService
            )
        }

        // Admin console
        adminRoutes(
            authService                = authService,
            adminService               = adminService,
            roleGroupService           = roleGroupService,
            tenantRepository           = tenantRepository,
            applicationRepository      = applicationRepository,
            userRepository             = userRepository,
            sessionRepository          = sessionRepository,
            auditLogRepository         = auditLogRepository,
            keyProvisioningService     = keyProvisioningService,
            mfaRepository              = mfaRepository,
            portalClientProvisioning   = portalClientProvisioning,
            identityProviderRepository = identityProviderRepository,
            apiKeyService              = apiKeyService
        )
    }
}
