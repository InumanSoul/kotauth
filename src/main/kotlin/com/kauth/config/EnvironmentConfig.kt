package com.kauth.config

import kotlin.system.exitProcess

/**
 * All environment-derived configuration, validated at startup.
 *
 * [load] reads env vars, applies fail-fast checks, and prints
 * warnings for non-fatal misconfigurations. Everything downstream
 * receives this data class — no service ever calls [System.getenv].
 */
data class EnvironmentConfig(
    val baseUrl: String,
    val env: String,
    val isDevelopment: Boolean,
    val secretKey: String?,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val isDemoMode: Boolean,
    val dbPoolMaxSize: Int,
    val dbPoolMinIdle: Int,
    val adminBypass: Boolean,
) {
    val isHttps: Boolean get() = baseUrl.startsWith("https://")

    companion object {
        fun load(): EnvironmentConfig {
            val baseUrl = requireBaseUrl()
            val env = System.getenv("KAUTH_ENV") ?: "development"
            val isDevelopment = env != "production"

            validateHttps(baseUrl, env)
            validateLegacySecret(env)

            val secretKey = System.getenv("KAUTH_SECRET_KEY")
            if (secretKey.isNullOrBlank()) {
                System.err.println(
                    """
                    [WARN] KAUTH_SECRET_KEY is not set.
                           SMTP passwords cannot be stored and portal sessions are ephemeral.
                           Set this env var to a random 32+ char string for production use.
                    """.trimIndent(),
                )
            }

            return EnvironmentConfig(
                baseUrl = baseUrl,
                env = env,
                isDevelopment = isDevelopment,
                secretKey = secretKey?.ifBlank { null },
                dbUrl =
                    System.getenv("DB_URL")
                        ?: "jdbc:postgresql://localhost:5432/kauth_db",
                dbUser = System.getenv("DB_USER") ?: "postgres",
                dbPassword = System.getenv("DB_PASSWORD") ?: "password",
                isDemoMode =
                    System
                        .getenv("KAUTH_DEMO_MODE")
                        ?.lowercase() == "true",
                dbPoolMaxSize = System.getenv("DB_POOL_MAX_SIZE")?.toIntOrNull() ?: 10,
                dbPoolMinIdle = System.getenv("DB_POOL_MIN_IDLE")?.toIntOrNull() ?: 2,
                adminBypass = System.getenv("KAUTH_ADMIN_BYPASS")?.lowercase() == "true",
            )
        }

        private fun requireBaseUrl(): String {
            val baseUrl = System.getenv("KAUTH_BASE_URL")
            if (baseUrl.isNullOrBlank()) {
                System.err.println(
                    """
                    ┌──────────────────────────────────────────────────────────┐
                    │  FATAL: KAUTH_BASE_URL environment variable is not set.  │
                    │                                                          │
                    │  This is required to generate correct issuer URLs in     │
                    │  OIDC tokens and discovery documents.                    │
                    │                                                          │
                    │  Example: KAUTH_BASE_URL=https://auth.yourdomain.com     │
                    │  Local:   KAUTH_BASE_URL=http://localhost:8080           │
                    └──────────────────────────────────────────────────────────┘
                    """.trimIndent(),
                )
                exitProcess(1)
            }
            return baseUrl
        }

        private fun validateHttps(
            baseUrl: String,
            env: String,
        ) {
            val isHttps = baseUrl.startsWith("https://")
            if (isHttps) return

            val isLocalhost =
                baseUrl.contains("localhost") ||
                    baseUrl.contains("127.0.0.1")

            when {
                env == "production" -> {
                    System.err.println(
                        """
                        ┌─────────────────────────────────────────────────────────────┐
                        │  FATAL: KAUTH_BASE_URL must use HTTPS in production mode.   │
                        │                                                             │
                        │  OAuth2 providers require HTTPS redirect URIs.              │
                        │  Session cookies require a secure transport layer.          │
                        │  OIDC discovery documents must be served over HTTPS.        │
                        │                                                             │
                        │  Set up TLS on your reverse proxy (nginx, Caddy, etc.)      │
                        │  and update KAUTH_BASE_URL to use https://.                 │
                        │                                                             │
                        │  Current value: $baseUrl
                        └─────────────────────────────────────────────────────────────┘
                        """.trimIndent(),
                    )
                    exitProcess(1)
                }
                !isLocalhost -> {
                    System.err.println(
                        """
                        [WARN] KAUTH_BASE_URL is not HTTPS and does not appear to be localhost.
                               Current value: $baseUrl
                               This will break identity federation — OAuth2 providers reject
                               non-HTTPS redirect URIs.
                        """.trimIndent(),
                    )
                }
                else -> {
                    System.err.println(
                        "[DEV]  KAUTH_BASE_URL is HTTP on localhost — acceptable for local development only.",
                    )
                }
            }
        }

        private fun validateLegacySecret(env: String) {
            if (env != "production") return
            val legacySecret = System.getenv("JWT_SECRET")
            if (!legacySecret.isNullOrBlank() && legacySecret == "secret-key-12345") {
                System.err.println(
                    "FATAL: JWT_SECRET is set to the insecure default value in production mode. Refusing to start.",
                )
                exitProcess(1)
            }
        }
    }
}
