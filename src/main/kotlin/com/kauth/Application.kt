package com.kauth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.kauth.adapter.persistence.PostgresUserRepository
import com.kauth.adapter.token.BcryptPasswordHasher
import com.kauth.adapter.token.JwtTokenAdapter
import com.kauth.adapter.web.admin.adminRoutes
import com.kauth.adapter.web.auth.authRoutes
import com.kauth.domain.service.AuthService
import com.kauth.infrastructure.DatabaseFactory
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*

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
 *       → UserRepository port  ← PostgresUserRepository
 *       → TokenPort             ← JwtTokenAdapter
 *       → PasswordHasher port   ← BcryptPasswordHasher
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
    // Infrastructure: serialization
    // -------------------------------------------------------------------------
    install(ContentNegotiation) { json() }

    // -------------------------------------------------------------------------
    // JWT verification (inbound token validation for protected routes)
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
    val tokenAdapter = JwtTokenAdapter(jwtIssuer, jwtAudience, algorithm)
    val passwordHasher = BcryptPasswordHasher()
    val authService = AuthService(userRepository, tokenAdapter, passwordHasher)

    // -------------------------------------------------------------------------
    // Routes — each module registers its own routes via extension functions
    // -------------------------------------------------------------------------
    routing {
        authRoutes(authService)

        // Admin routes: JWT-protected, separate module, same domain ports
        authenticate("auth-jwt") {
            adminRoutes()
        }
    }
}
