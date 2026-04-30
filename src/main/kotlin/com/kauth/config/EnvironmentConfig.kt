package com.kauth.config

import kotlin.system.exitProcess

/** Database connection config — loadable independently for CLI commands that don't need the full server config. */
data class DbConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val dbPoolMaxSize: Int = 10,
    val dbPoolMinIdle: Int = 2,
) {
    companion object {
        fun load(): DbConfig =
            DbConfig(
                dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/kauth_db",
                dbUser = System.getenv("DB_USER") ?: "postgres",
                dbPassword = System.getenv("DB_PASSWORD") ?: "password",
                dbPoolMaxSize = System.getenv("DB_POOL_MAX_SIZE")?.toIntOrNull() ?: 10,
                dbPoolMinIdle = System.getenv("DB_POOL_MIN_IDLE")?.toIntOrNull() ?: 2,
            )
    }
}

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
    val secretKey: String,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val isDemoMode: Boolean,
    val dbPoolMaxSize: Int,
    val dbPoolMinIdle: Int,
    val updateCheckEnabled: Boolean,
    val updateCheckUrl: String,
    val i18nBundleDir: String?,
    val redisUrl: String?,
    val redisUsername: String?,
    val redisPassword: String?,
    val redisTimeoutMs: Long,
    val redisStartupProbeTimeoutMs: Long,
    val redisCommandTimeoutMs: Long,
    val ssoSessionTtlSeconds: Long,
    val ssoSessionMaxTtlSeconds: Long,
) {
    val isHttps: Boolean get() = baseUrl.startsWith("https://")
    val redisEnabled: Boolean get() = redisUrl != null

    companion object {
        fun load(): EnvironmentConfig {
            val baseUrl = requireBaseUrl()
            val env = System.getenv("KAUTH_ENV") ?: "development"
            val isDevelopment = env != "production"

            validateHttps(baseUrl, env)
            validateLegacySecret(env)

            val secretKey = requireSecretKey(System.getenv("KAUTH_SECRET_KEY"))

            val adminBypass = System.getenv("KAUTH_ADMIN_BYPASS")?.lowercase() == "true"
            validateAdminBypass(adminBypass)

            val ssoTtl = System.getenv("KAUTH_SSO_SESSION_TTL_SECONDS")?.toLongOrNull() ?: 86_400L
            val ssoMaxTtl = System.getenv("KAUTH_SSO_SESSION_MAX_TTL_SECONDS")?.toLongOrNull() ?: 2_592_000L
            validateSsoTtls(ssoTtl, ssoMaxTtl)

            return EnvironmentConfig(
                baseUrl = baseUrl,
                env = env,
                isDevelopment = isDevelopment,
                secretKey = secretKey,
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
                updateCheckEnabled = System.getenv("KAUTH_UPDATE_CHECK")?.lowercase() != "false",
                updateCheckUrl =
                    System.getenv("KAUTH_UPDATE_CHECK_URL")
                        ?: "https://inumansoul.github.io/kotauth/latest.json",
                i18nBundleDir = System.getenv("KAUTH_I18N_BUNDLE_DIR")?.takeIf { it.isNotBlank() },
                redisUrl = requireRedisUrl(System.getenv("KAUTH_REDIS_URL")),
                redisUsername = System.getenv("KAUTH_REDIS_USERNAME")?.takeIf { it.isNotBlank() },
                redisPassword = System.getenv("KAUTH_REDIS_PASSWORD")?.takeIf { it.isNotBlank() },
                redisTimeoutMs = System.getenv("KAUTH_REDIS_TIMEOUT_MS")?.toLongOrNull() ?: 250L,
                redisStartupProbeTimeoutMs =
                    System.getenv("KAUTH_REDIS_STARTUP_PROBE_TIMEOUT_MS")?.toLongOrNull() ?: 2000L,
                redisCommandTimeoutMs = System.getenv("KAUTH_REDIS_COMMAND_TIMEOUT_MS")?.toLongOrNull() ?: 100L,
                ssoSessionTtlSeconds = ssoTtl,
                ssoSessionMaxTtlSeconds = ssoMaxTtl,
            )
        }

        private fun validateSsoTtls(
            ttl: Long,
            maxTtl: Long,
        ) {
            if (ttl < 60 || maxTtl < 60 || ttl > maxTtl) {
                System.err.println(
                    """
                    ┌──────────────────────────────────────────────────────────────┐
                    │  FATAL: invalid SSO session TTL configuration.               │
                    │                                                              │
                    │  KAUTH_SSO_SESSION_TTL_SECONDS must be ≥ 60 and ≤            │
                    │  KAUTH_SSO_SESSION_MAX_TTL_SECONDS.                          │
                    │                                                              │
                    │  Current: ttl=$ttl, maxTtl=$maxTtl
                    └──────────────────────────────────────────────────────────────┘
                    """.trimIndent(),
                )
                exitProcess(1)
            }
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

        private fun requireSecretKey(secretKey: String?): String {
            if (secretKey.isNullOrBlank() || secretKey.length < 32) {
                System.err.println(
                    """
                    ┌──────────────────────────────────────────────────────────────┐
                    │  FATAL: KAUTH_SECRET_KEY must be set (32+ chars).           │
                    │                                                              │
                    │  This key is used for:                                       │
                    │    • AES-256-GCM encryption of secrets at rest               │
                    │    • HMAC signing of session cookies                         │
                    │    • Encrypting SMTP credentials and TOTP secrets            │
                    │    • Encrypting RSA private keys in the database             │
                    │                                                              │
                    │  Generate one:  openssl rand -hex 32                         │
                    └──────────────────────────────────────────────────────────────┘
                    """.trimIndent(),
                )
                exitProcess(1)
            }
            return secretKey
        }

        private fun validateAdminBypass(adminBypass: Boolean) {
            if (adminBypass) {
                System.err.println(
                    """
                    ┌──────────────────────────────────────────────────────────────┐
                    │  FATAL: KAUTH_ADMIN_BYPASS is no longer supported.          │
                    │                                                              │
                    │  This flag has been removed because it disables MFA          │
                    │  enforcement and creates untracked sessions that cannot      │
                    │  be revoked.                                                 │
                    │                                                              │
                    │  For emergency admin recovery, use the CLI recovery tool:    │
                    │    java -jar kauth.jar cli reset-admin-mfa --username=admin  │
                    └──────────────────────────────────────────────────────────────┘
                    """.trimIndent(),
                )
                exitProcess(1)
            }
        }

        private fun requireRedisUrl(raw: String?): String? {
            val url = raw?.takeIf { it.isNotBlank() } ?: return null
            if (!url.startsWith("redis://") && !url.startsWith("rediss://")) {
                System.err.println(
                    """
                    ┌──────────────────────────────────────────────────────────────┐
                    │  FATAL: KAUTH_REDIS_URL has an unsupported scheme.           │
                    │                                                              │
                    │  Expected: redis://host[:port][/db]                          │
                    │       or:  rediss://host[:port][/db]   (TLS)                 │
                    │                                                              │
                    │  Credentials belong in KAUTH_REDIS_USERNAME and              │
                    │  KAUTH_REDIS_PASSWORD, not in the URL.                       │
                    │                                                              │
                    │  Current value: $url
                    └──────────────────────────────────────────────────────────────┘
                    """.trimIndent(),
                )
                exitProcess(1)
            }
            return url
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
