package com.kauth

import com.kauth.adapter.persistence.PostgresApplicationRepository
import com.kauth.adapter.persistence.PostgresAuditLogAdapter
import com.kauth.adapter.persistence.PostgresAuditLogRepository
import com.kauth.adapter.persistence.PostgresAuthorizationCodeRepository
import com.kauth.adapter.persistence.PostgresSessionRepository
import com.kauth.adapter.persistence.PostgresTenantKeyRepository
import com.kauth.adapter.persistence.PostgresTenantRepository
import com.kauth.adapter.persistence.PostgresUserRepository
import com.kauth.adapter.token.BcryptPasswordHasher
import com.kauth.adapter.token.JwtTokenAdapter
import com.kauth.adapter.web.admin.AdminSession
import com.kauth.adapter.web.admin.AdminView
import com.kauth.adapter.web.admin.adminRoutes
import com.kauth.adapter.web.auth.authRoutes
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.OAuthService
import com.kauth.infrastructure.DatabaseFactory
import com.kauth.infrastructure.KeyProvisioningService
import com.kauth.infrastructure.RateLimiter
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
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

    val env = System.getenv("KAUTH_ENV") ?: "development"
    if (env == "production") {
        val legacySecret = System.getenv("JWT_SECRET")
        if (!legacySecret.isNullOrBlank() && legacySecret == "secret-key-12345") {
            System.err.println("FATAL: JWT_SECRET is set to the insecure default value in production mode. Refusing to start.")
            exitProcess(1)
        }
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

    // -------------------------------------------------------------------------
    // RS256 key provisioning — ensure every tenant has a signing key
    // -------------------------------------------------------------------------
    val keyProvisioning = KeyProvisioningService(tenantRepository, tenantKeyRepository)
    keyProvisioning.provisionMissingKeys()

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
    val authService = AuthService(userRepository, tenantRepository, tokenAdapter, passwordHasher, auditLogAdapter)
    val oauthService = OAuthService(
        tenantRepository      = tenantRepository,
        userRepository        = userRepository,
        applicationRepository = applicationRepository,
        sessionRepository     = sessionRepository,
        authCodeRepository    = authCodeRepository,
        tokenPort             = tokenAdapter,
        passwordHasher        = passwordHasher,
        auditLog              = auditLogAdapter
    )
    val adminService = AdminService(
        tenantRepository      = tenantRepository,
        userRepository        = userRepository,
        applicationRepository = applicationRepository,
        passwordHasher        = passwordHasher,
        auditLog              = auditLogAdapter
    )

    // -------------------------------------------------------------------------
    // Rate limiters (Phase 0)
    // -------------------------------------------------------------------------
    val loginRateLimiter    = RateLimiter(maxRequests = 5,  windowSeconds = 60)   // 5 attempts / minute per IP
    val registerRateLimiter = RateLimiter(maxRequests = 3,  windowSeconds = 300)  // 3 registrations / 5 min per IP

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(
            authService            = authService,
            oauthService           = oauthService,
            adminService           = adminService,
            tenantRepository       = tenantRepository,
            applicationRepository  = applicationRepository,
            userRepository         = userRepository,
            sessionRepository      = sessionRepository,
            auditLogRepository     = auditLogRepository,
            keyProvisioningService = keyProvisioning,
            loginRateLimiter       = loginRateLimiter,
            registerRateLimiter    = registerRateLimiter
        )
    }.start(wait = true)
}

fun Application.module(
    authService            : AuthService,
    oauthService           : OAuthService,
    adminService           : AdminService,
    tenantRepository       : com.kauth.domain.port.TenantRepository,
    applicationRepository  : com.kauth.domain.port.ApplicationRepository,
    userRepository         : com.kauth.domain.port.UserRepository,
    sessionRepository      : com.kauth.domain.port.SessionRepository,
    auditLogRepository     : com.kauth.domain.port.AuditLogRepository,
    keyProvisioningService : KeyProvisioningService,
    loginRateLimiter       : RateLimiter,
    registerRateLimiter    : RateLimiter
) {
    // -------------------------------------------------------------------------
    // Plugins
    // -------------------------------------------------------------------------
    install(ContentNegotiation) { json() }

    install(Sessions) {
        cookie<AdminSession>("KOTAUTH_ADMIN") {
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = 3600 * 8  // 8-hour admin session
            // TODO (Phase 3): cookie.secure = true once TLS is enforced
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

        // Health check (Phase 5 will expand this)
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        // All tenant auth + OIDC flows
        authRoutes(
            authService      = authService,
            oauthService     = oauthService,
            tenantRepository = tenantRepository,
            loginRateLimiter    = loginRateLimiter,
            registerRateLimiter = registerRateLimiter
        )

        // Admin console
        adminRoutes(
            authService            = authService,
            adminService           = adminService,
            tenantRepository       = tenantRepository,
            applicationRepository  = applicationRepository,
            userRepository         = userRepository,
            sessionRepository      = sessionRepository,
            auditLogRepository     = auditLogRepository,
            keyProvisioningService = keyProvisioningService
        )
    }
}
