package com.kauth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.kauth.adapter.persistence.PostgresTenantRepository
import com.kauth.adapter.persistence.PostgresUserRepository
import com.kauth.adapter.token.BcryptPasswordHasher
import com.kauth.adapter.token.JwtTokenAdapter
import com.kauth.adapter.web.admin.AdminSession
import com.kauth.adapter.web.admin.adminRoutes
import com.kauth.adapter.web.auth.authRoutes
import com.kauth.domain.service.AuthService
import com.kauth.infrastructure.DatabaseFactory
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

/**
 * KotAuth — Composition Root
 *
 * This file's only job is to wire dependencies together and start the server.
 * No business logic, no HTML, no SQL. If you're reading this to understand
 * what the system DOES, start at AuthService. If you're reading it to understand
 * how the pieces connect, you're in the right place.
 *
 * Dependency flow (outermost → innermost):
 *   HTTP (Ktor routes)
 *     → AuthService (domain use cases)
 *       → UserRepository port    ← PostgresUserRepository
 *       → TenantRepository port  ← PostgresTenantRepository
 *       → TokenPort              ← JwtTokenAdapter
 *       → PasswordHasher port    ← BcryptPasswordHasher
 *
 * Auth strategy:
 *   - Auth API (/t/{slug}/...)  → JWT tokens, stateless
 *   - Admin console (/admin/…)  → Cookie sessions, server-side (AdminSession)
 */
fun main() {
    DatabaseFactory.init(
        url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/kauth_db",
        user = System.getenv("DB_USER") ?: "postgres",
        password = System.getenv("DB_PASSWORD") ?: "password"
    )

    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // -------------------------------------------------------------------------
    // Plugins
    // -------------------------------------------------------------------------
    install(ContentNegotiation) { json() }

    install(Sessions) {
        cookie<AdminSession>("KOTAUTH_ADMIN") {
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = 3600 * 8  // 8-hour admin session
            // TODO (Phase 2): cookie.secure = true when TLS is in place
        }
    }

    // -------------------------------------------------------------------------
    // JWT verification — inbound token validation for protected API routes
    // -------------------------------------------------------------------------
    val jwtIssuer = "https://kauth.example.com"
    val jwtAudience = "kauth-clients"
    val algorithm = Algorithm.HMAC256(System.getenv("JWT_SECRET") ?: "secret-key-12345")

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                JWT.require(algorithm)
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("username").asString().isNotBlank())
                    JWTPrincipal(credential.payload)
                else null
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dependency wiring
    // -------------------------------------------------------------------------
    val userRepository = PostgresUserRepository()
    val tenantRepository = PostgresTenantRepository()
    val tokenAdapter = JwtTokenAdapter(jwtIssuer, jwtAudience, algorithm)
    val passwordHasher = BcryptPasswordHasher()
    val authService = AuthService(userRepository, tenantRepository, tokenAdapter, passwordHasher)

    // -------------------------------------------------------------------------
    // Routes
    // -------------------------------------------------------------------------
    routing {
        // Serve static assets — CSS files from src/main/resources/static/
        staticResources("/static", "static")

        // Root redirect — navigating to / takes you to the master tenant login
        get("/") {
            call.respondRedirect("/t/master/login", permanent = false)
        }

        // Public auth flows — one route block covers all tenants
        authRoutes(authService, tenantRepository)

        // Admin console — session-auth managed internally by adminRoutes
        adminRoutes(authService, tenantRepository)

        // JWT-protected API example — wire real API routes here in Phase 2
        authenticate("auth-jwt") {
            get("/api/me") {
                val principal = call.principal<JWTPrincipal>()!!
                call.respond(mapOf("username" to principal.payload.getClaim("username").asString()))
            }
        }
    }
}
