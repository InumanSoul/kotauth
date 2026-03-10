package com.kauth

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.html.*
import kotlinx.html.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import java.util.*

/**
 * KotAuth POC Architecture (V2.2)
 */

fun main() {
    val dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/kauth_db"
    val dbUser = System.getenv("DB_USER") ?: "postgres"
    val dbPass = System.getenv("DB_PASSWORD") ?: "password"

    Database.connect(
        url = dbUrl,
        driver = "org.postgresql.Driver",
        user = dbUser,
        password = dbPass
    )

    transaction {
        SchemaUtils.create(Users)
        if (Users.selectAll().empty()) {
            Users.insert {
                it[username] = "admin"
                it[passwordHash] = "password"
                it[fullName] = "KotAuth Administrator"
            }
        }
    }

    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val username = varchar("username", 50).uniqueIndex()
    val passwordHash = varchar("password_hash", 128)
    val fullName = varchar("full_name", 100)
    override val primaryKey = PrimaryKey(id)
}

fun Application.module() {
    install(ContentNegotiation) { json() }

    val jwtIssuer = "https://kauth.example.com"
    val jwtAudience = "kauth-clients"
    val algorithm = Algorithm.HMAC256(System.getenv("JWT_SECRET") ?: "secret-key-12345")

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(JWT.require(algorithm).withAudience(jwtAudience).withIssuer(jwtIssuer).build())
            validate { credential ->
                if (credential.payload.getClaim("username").asString() != "") JWTPrincipal(credential.payload) else null
            }
        }
    }

    val userService = PostgresUserService()
    val tokenService = TokenService(jwtIssuer, jwtAudience, algorithm)

    routing {
        get("/login") {
            call.respondHtml(HttpStatusCode.OK) {
                head {
                    title { +"KotAuth | Login" }
                    style {
                        unsafe {
                            +"""
                            body { background: #121212; color: white; font-family: 'Inter', sans-serif; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
                            .login-card { background: #1e1e1e; padding: 2rem; border-radius: 8px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); width: 100%; max-width: 400px; }
                            h2 { text-align: center; color: #bb86fc; }
                            input { width: 100%; padding: 12px; margin: 10px 0; background: #2c2c2c; border: 1px solid #333; color: white; border-radius: 4px; box-sizing: border-box; }
                            button { width: 100%; padding: 12px; background: #bb86fc; border: none; border-radius: 4px; color: #121212; font-weight: bold; cursor: pointer; transition: 0.3s; }
                            button:hover { background: #9965f4; }
                            .footer { text-align: center; margin-top: 1rem; font-size: 0.8rem; color: #666; }
                            """
                        }
                    }
                }
                body {
                    div("login-card") {
                        h2 { +"KotAuth Identity" }
                        form(action = "/login", encType = FormEncType.applicationXWwwFormUrlEncoded, method = FormMethod.post) {
                            input(type = InputType.text, name = "username") { placeholder = "Username" }
                            input(type = InputType.password, name = "password") { placeholder = "Password" }
                            button(type = ButtonType.submit) { +"Sign In" }
                        }
                        div("footer") { +"Modernized IAM Solution" }
                    }
                }
            }
        }

        post("/login") {
            val params = call.receiveParameters()
            val user = userService.authenticate(params["username"] ?: "", params["password"] ?: "")
            if (user != null) {
                val tokens = tokenService.createTokenSet(user)
                call.respond(tokens)
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Invalid Database Credentials")
            }
        }

        get("/.well-known/openid-configuration") {
            call.respond(mapOf("issuer" to jwtIssuer, "token_endpoint" to "${jwtIssuer}/token"))
        }

        post("/token") {
            val params = call.receiveParameters()
            val user = userService.authenticate(params["username"] ?: "", params["password"] ?: "")
            if (user != null) call.respond(tokenService.createTokenSet(user))
            else call.respond(HttpStatusCode.Unauthorized)
        }
    }
}

class PostgresUserService {
    fun authenticate(user: String, pass: String): String? = transaction {
        Users.selectAll().where { (Users.username eq user) and (Users.passwordHash eq pass) }
            .map { it[Users.username] }
            .singleOrNull()
    }
}

@Serializable
data class TokenResponse(val access_token: String, val refresh_token: String, val expires_in: Long)

class TokenService(private val issuer: String, private val audience: String, private val algorithm: Algorithm) {
    fun createTokenSet(username: String): TokenResponse {
        val token = JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("username", username)
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
            .sign(algorithm)
        return TokenResponse(token, UUID.randomUUID().toString(), 3600)
    }
}