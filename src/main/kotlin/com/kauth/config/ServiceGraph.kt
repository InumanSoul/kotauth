package com.kauth.config

import com.kauth.adapter.email.SmtpEmailAdapter
import com.kauth.adapter.persistence.PostgresApiKeyRepository
import com.kauth.adapter.persistence.PostgresApplicationRepository
import com.kauth.adapter.persistence.PostgresAuditLogAdapter
import com.kauth.adapter.persistence.PostgresAuditLogRepository
import com.kauth.adapter.persistence.PostgresAuthorizationCodeRepository
import com.kauth.adapter.persistence.PostgresCorsAdapter
import com.kauth.adapter.persistence.PostgresEmailOtpChallengeRepository
import com.kauth.adapter.persistence.PostgresEmailVerificationTokenRepository
import com.kauth.adapter.persistence.PostgresGroupRepository
import com.kauth.adapter.persistence.PostgresIdentityProviderRepository
import com.kauth.adapter.persistence.PostgresMfaRepository
import com.kauth.adapter.persistence.PostgresPasswordPolicyAdapter
import com.kauth.adapter.persistence.PostgresPasswordResetTokenRepository
import com.kauth.adapter.persistence.PostgresPortalConfigRepository
import com.kauth.adapter.persistence.PostgresRoleRepository
import com.kauth.adapter.persistence.PostgresSessionRepository
import com.kauth.adapter.persistence.PostgresSocialAccountRepository
import com.kauth.adapter.persistence.PostgresTenantClaimMapperRepository
import com.kauth.adapter.persistence.PostgresTenantEmailBrandingRepository
import com.kauth.adapter.persistence.PostgresTenantKeyRepository
import com.kauth.adapter.persistence.PostgresTenantRepository
import com.kauth.adapter.persistence.PostgresThemeRepository
import com.kauth.adapter.persistence.PostgresUserAttributeRepository
import com.kauth.adapter.persistence.PostgresUserRepository
import com.kauth.adapter.persistence.PostgresWebhookDeliveryRepository
import com.kauth.adapter.persistence.PostgresWebhookEndpointRepository
import com.kauth.adapter.social.GitHubOAuthAdapter
import com.kauth.adapter.social.GoogleOAuthAdapter
import com.kauth.adapter.token.BcryptPasswordHasher
import com.kauth.adapter.token.JwtTokenAdapter
import com.kauth.adapter.web.plugin.CorsOriginCache
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.BackupEncryptionPort
import com.kauth.domain.port.EmailOtpChallengeRepository
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.MfaRepository
import com.kauth.domain.port.PortalConfigRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantEmailBrandingRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.ThemeRepository
import com.kauth.domain.port.TranslationPort
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AccountSelfService
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.AdminUserService
import com.kauth.domain.service.ApiKeyBootstrapService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.ApplicationManagementService
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.BackupExporterService
import com.kauth.domain.service.BackupImporterService
import com.kauth.domain.service.CorsService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.EmailOtpService
import com.kauth.domain.service.ImpersonationService
import com.kauth.domain.service.KeyRotationService
import com.kauth.domain.service.LauncherService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.SocialLoginService
import com.kauth.domain.service.UserAttributeService
import com.kauth.domain.service.WebhookService
import com.kauth.domain.service.WorkspaceSettingsService
import com.kauth.infrastructure.AdminClientProvisioning
import com.kauth.infrastructure.BundleTranslation
import com.kauth.infrastructure.CachingClaimMapperService
import com.kauth.infrastructure.DemoSeedService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import com.kauth.infrastructure.ExposedTransactionRunner
import com.kauth.infrastructure.FlywaySchemaHead
import com.kauth.infrastructure.InMemoryRateLimiter
import com.kauth.infrastructure.KeyEncryptionMigration
import com.kauth.infrastructure.KeyProvisioningService
import com.kauth.infrastructure.Pbkdf2AesGcmBackupEncryption
import com.kauth.infrastructure.PortalClientProvisioning
import com.kauth.infrastructure.redis.RedisClientFactory
import com.kauth.infrastructure.redis.RedisClientHolder
import com.kauth.infrastructure.redis.RedisRateLimiter
import com.kauth.infrastructure.redis.RedisSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds every service and repository needed by the Ktor module.
 * Built once at startup by [create], then passed into the server.
 *
 * ArrayInDataClass is suppressed: ByteArray session keys would use
 * reference equality in the auto-generated equals/hashCode, but this
 * container is never compared — it is a DI singleton.
 */
@Suppress("ArrayInDataClass")
data class ServiceGraph(
    val authService: AuthService,
    val oauthService: OAuthService,
    val accountService: AdminAccountService,
    val workspaceSettingsService: WorkspaceSettingsService,
    val adminUserService: AdminUserService,
    val applicationManagementService: ApplicationManagementService,
    val roleGroupService: RoleGroupService,
    val launcherService: LauncherService,
    val impersonationService: ImpersonationService,
    val credentialFlowService: CredentialFlowService,
    val accountSelfService: AccountSelfService,
    val mfaService: MfaService,
    val socialLoginService: SocialLoginService,
    val emailOtpService: EmailOtpService,
    val apiKeyService: ApiKeyService,
    val apiKeyBootstrapService: ApiKeyBootstrapService,
    val webhookService: WebhookService,
    val corsService: CorsService,
    val corsOriginCache: CorsOriginCache,
    val tenantRepository: TenantRepository,
    val applicationRepository: ApplicationRepository,
    val userRepository: UserRepository,
    val sessionRepository: SessionRepository,
    val auditLogRepository: AuditLogRepository,
    val mfaRepository: MfaRepository,
    val roleRepository: RoleRepository,
    val groupRepository: GroupRepository,
    val identityProviderRepository: IdentityProviderRepository,
    val portalConfigRepository: PortalConfigRepository,
    val themeRepository: ThemeRepository,
    val emailBrandingRepository: TenantEmailBrandingRepository,
    val emailOtpChallengeRepository: EmailOtpChallengeRepository,
    val keyProvisioningService: KeyProvisioningService,
    val portalClientProvisioning: PortalClientProvisioning,
    val adminClientProvisioning: AdminClientProvisioning,
    val adminSessionKey: ByteArray,
    val loginRateLimiter: RateLimiterPort,
    val registerRateLimiter: RateLimiterPort,
    val tokenRateLimiter: RateLimiterPort,
    val mfaRateLimiter: RateLimiterPort,
    val otpEmailRateLimiter: RateLimiterPort,
    val otpIpRateLimiter: RateLimiterPort,
    val portalSessionKey: ByteArray,
    val encryptionService: EncryptionService,
    val socialAccountRepository: PostgresSocialAccountRepository,
    val keyRotationService: KeyRotationService,
    val tenantKeyRepository: PostgresTenantKeyRepository,
    val userAttributeService: UserAttributeService,
    val claimMapperService: CachingClaimMapperService,
    val translationPort: TranslationPort,
    val redisClientHolder: RedisClientHolder?,
    val applicationScope: CoroutineScope,
    val backupExporterService: BackupExporterService,
    val backupImporterService: BackupImporterService,
    val backupEncryptionPort: BackupEncryptionPort,
    val auditLogPort: AuditLogPort,
    /** Flyway head V-number captured at startup; embedded in backup exports. */
    val flywaySchemaVersion: Int,
) {
    companion object {
        fun create(config: EnvironmentConfig): ServiceGraph {
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val encryptionService = EncryptionService(config.secretKey)

            // -- Redis client (optional) --------------------------------------
            // Constructed first so both session storage and rate limiters can
            // branch on the same holder. Without `KAUTH_REDIS_URL`, this stays
            // null and the storage falls back to PostgreSQL + InMemory.
            val redisClientHolder: RedisClientHolder? =
                config.redisUrl?.let { url ->
                    RedisClientFactory.create(
                        url = url,
                        username = config.redisUsername,
                        password = config.redisPassword,
                        connectTimeoutMs = config.redisTimeoutMs,
                        commandTimeoutMs = config.redisCommandTimeoutMs,
                    )
                }

            // -- Repositories -------------------------------------------------
            val userRepository = PostgresUserRepository()
            val tenantRepository = PostgresTenantRepository(encryptionService)
            val applicationRepository = PostgresApplicationRepository()
            val tenantKeyRepository = PostgresTenantKeyRepository(encryptionService)
            val sessionRepository: SessionRepository =
                redisClientHolder?.let { RedisSessionRepository(it.commands) }
                    ?: PostgresSessionRepository()
            val authCodeRepository = PostgresAuthorizationCodeRepository()
            val auditLogRepository = PostgresAuditLogRepository()
            val passwordHasher = BcryptPasswordHasher()
            val evTokenRepository = PostgresEmailVerificationTokenRepository()
            val prTokenRepository = PostgresPasswordResetTokenRepository()
            val roleRepository = PostgresRoleRepository()
            val groupRepository = PostgresGroupRepository()
            val breachedPasswordChecker = com.kauth.infrastructure.HibpBreachedPasswordAdapter()
            val passwordPolicyAdapter =
                PostgresPasswordPolicyAdapter(
                    passwordHasher = passwordHasher,
                    breachedPasswordChecker = breachedPasswordChecker,
                )
            val identityProviderRepository =
                PostgresIdentityProviderRepository(encryptionService)
            val socialAccountRepository = PostgresSocialAccountRepository()
            val portalConfigRepository = PostgresPortalConfigRepository()
            val themeRepository = PostgresThemeRepository()
            val emailBrandingRepository = PostgresTenantEmailBrandingRepository()
            val emailOtpChallengeRepository = PostgresEmailOtpChallengeRepository()
            val apiKeyRepository = PostgresApiKeyRepository()
            val webhookEndpointRepository = PostgresWebhookEndpointRepository()
            val webhookDeliveryRepository = PostgresWebhookDeliveryRepository()
            val mfaRepository = PostgresMfaRepository(encryptionService)
            val userAttributeRepository = PostgresUserAttributeRepository()
            val tenantClaimMapperRepository = PostgresTenantClaimMapperRepository()
            val corsOriginCache = CorsOriginCache(PostgresCorsAdapter())
            val corsService = CorsService(corsOriginCache)

            // -- Key provisioning ---------------------------------------------
            val keyProvisioning =
                KeyProvisioningService(tenantRepository, tenantKeyRepository)
            keyProvisioning.provisionMissingKeys()
            KeyEncryptionMigration(encryptionService).migrateIfNeeded()

            val portalClientProvisioning =
                PortalClientProvisioning(
                    tenantRepository = tenantRepository,
                    applicationRepository = applicationRepository,
                    baseUrl = config.baseUrl,
                )
            portalClientProvisioning.provisionRedirectUris()

            val adminClientProvisioning =
                AdminClientProvisioning(
                    tenantRepository = tenantRepository,
                    applicationRepository = applicationRepository,
                    themeRepository = themeRepository,
                    baseUrl = config.baseUrl,
                )
            adminClientProvisioning.provision()

            // -- Token adapter ------------------------------------------------
            val tokenAdapter =
                JwtTokenAdapter(
                    baseUrl = config.baseUrl,
                    tenantKeyRepository = tenantKeyRepository,
                )

            // -- Webhook + audit adapter --------------------------------------
            val webhookService =
                WebhookService(
                    endpointRepository = webhookEndpointRepository,
                    deliveryRepository = webhookDeliveryRepository,
                    scope = applicationScope,
                )
            val auditLogAdapter =
                PostgresAuditLogAdapter(webhookService = webhookService)

            val translationPort: TranslationPort =
                config.i18nBundleDir
                    ?.let {
                        BundleTranslation(
                            java.nio.file.Paths
                                .get(it),
                        )
                    }
                    ?: EnglishOnlyTranslation()

            val emailAdapter = SmtpEmailAdapter(translationPort)
            val credentialFlowService =
                CredentialFlowService(
                    userRepository = userRepository,
                    tenantRepository = tenantRepository,
                    sessionRepository = sessionRepository,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                    evTokenRepo = evTokenRepository,
                    prTokenRepo = prTokenRepository,
                    emailPort = emailAdapter,
                    passwordPolicy = passwordPolicyAdapter,
                    emailScope = applicationScope,
                )
            val accountSelfService =
                AccountSelfService(
                    userRepository = userRepository,
                    tenantRepository = tenantRepository,
                    sessionRepository = sessionRepository,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                    emailPort = emailAdapter,
                    passwordPolicy = passwordPolicyAdapter,
                    emailScope = applicationScope,
                )
            val authService =
                AuthService(
                    userRepository = userRepository,
                    tenantRepository = tenantRepository,
                    tokenPort = tokenAdapter,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                    sessionRepository = sessionRepository,
                    credentialFlowService = credentialFlowService,
                    passwordPolicy = passwordPolicyAdapter,
                    applicationRepository = applicationRepository,
                    roleRepository = roleRepository,
                )
            // -- User attributes + claim mapping ------------------------------
            val userAttributeService =
                UserAttributeService(
                    userAttributeRepository = userAttributeRepository,
                    userRepository = userRepository,
                )
            val claimMapperService =
                CachingClaimMapperService(
                    mapperRepository = tenantClaimMapperRepository,
                )

            val oauthService =
                OAuthService(
                    tenantRepository = tenantRepository,
                    userRepository = userRepository,
                    applicationRepository = applicationRepository,
                    sessionRepository = sessionRepository,
                    authCodeRepository = authCodeRepository,
                    tokenPort = tokenAdapter,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                    roleRepository = roleRepository,
                    userAttributeRepository = userAttributeRepository,
                    claimMappersFor = claimMapperService::list,
                )
            val emailOtpService =
                EmailOtpService(
                    tenantRepository = tenantRepository,
                    userRepository = userRepository,
                    challengeRepository = emailOtpChallengeRepository,
                    applicationRepository = applicationRepository,
                    authorizationCodeRepository = authCodeRepository,
                    emailPort = emailAdapter,
                    auditLog = auditLogAdapter,
                    roleRepository = roleRepository,
                )
            val apiKeyBootstrapService =
                ApiKeyBootstrapService(apiKeyRepository, tenantRepository)
            val accountService =
                AdminAccountService(
                    tenantRepository = tenantRepository,
                    userRepository = userRepository,
                    auditLog = auditLogAdapter,
                    credentialFlowService = credentialFlowService,
                )
            val applicationManagementService =
                ApplicationManagementService(
                    applicationRepository = applicationRepository,
                    tenantRepository = tenantRepository,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                    corsPort = corsOriginCache,
                )
            val adminUserService =
                AdminUserService(
                    tenantRepository = tenantRepository,
                    userRepository = userRepository,
                    sessionRepository = sessionRepository,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                    credentialFlowService = credentialFlowService,
                    passwordPolicy = passwordPolicyAdapter,
                    emailPort = emailAdapter,
                )
            val workspaceSettingsService =
                WorkspaceSettingsService(
                    tenantRepository = tenantRepository,
                    auditLog = auditLogAdapter,
                    themeRepository = themeRepository,
                    portalConfigRepository = portalConfigRepository,
                    emailBrandingRepository = emailBrandingRepository,
                    corsPort = corsOriginCache,
                )
            val roleGroupService =
                RoleGroupService(
                    roleRepository = roleRepository,
                    groupRepository = groupRepository,
                    tenantRepository = tenantRepository,
                    userRepository = userRepository,
                    applicationRepository = applicationRepository,
                    auditLog = auditLogAdapter,
                )
            val launcherService =
                LauncherService(
                    applicationRepository = applicationRepository,
                    roleRepository = roleRepository,
                )
            val impersonationService =
                ImpersonationService(
                    userRepository = userRepository,
                    tenantRepository = tenantRepository,
                    sessionRepository = sessionRepository,
                    tokenPort = tokenAdapter,
                    auditLog = auditLogAdapter,
                )
            val mfaService =
                MfaService(
                    mfaRepository = mfaRepository,
                    userRepository = userRepository,
                    tenantRepository = tenantRepository,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                )
            val apiKeyService =
                ApiKeyService(
                    apiKeyRepository = apiKeyRepository,
                    tenantRepository = tenantRepository,
                )
            val socialLoginService =
                SocialLoginService(
                    identityProviderRepository = identityProviderRepository,
                    socialAccountRepository = socialAccountRepository,
                    userRepository = userRepository,
                    tenantRepository = tenantRepository,
                    sessionRepository = sessionRepository,
                    tokenPort = tokenAdapter,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                    providerAdapters =
                        mapOf(
                            SocialProvider.GOOGLE to GoogleOAuthAdapter(),
                            SocialProvider.GITHUB to GitHubOAuthAdapter(),
                        ),
                    applicationRepository = applicationRepository,
                    roleRepository = roleRepository,
                )

            // -- Key rotation -------------------------------------------------
            val keyRotationService =
                KeyRotationService(
                    tenantKeyRepository = tenantKeyRepository,
                    tenantRepository = tenantRepository,
                    tokenPort = tokenAdapter,
                    auditLog = auditLogAdapter,
                )

            // -- Demo seed ----------------------------------------------------
            if (config.isDemoMode) {
                DemoSeedService(
                    tenantRepository = tenantRepository,
                    userRepository = userRepository,
                    applicationRepository = applicationRepository,
                    passwordHasher = passwordHasher,
                    keyProvisioningService = keyProvisioning,
                    portalClientProvisioning = portalClientProvisioning,
                    roleGroupService = roleGroupService,
                    roleRepository = roleRepository,
                    auditLog = auditLogAdapter,
                    webhookEndpointRepository = webhookEndpointRepository,
                    themeRepository = themeRepository,
                    baseUrl = config.baseUrl,
                ).seedIfEmpty()
            }

            // -- Rate limiters ------------------------------------------------
            fun buildRateLimiter(
                max: Int,
                windowSecs: Long,
                prefix: String,
            ): RateLimiterPort =
                redisClientHolder?.let {
                    RedisRateLimiter(
                        commands = it.commands,
                        maxRequests = max,
                        windowSeconds = windowSecs,
                        keyPrefix = prefix,
                    )
                } ?: InMemoryRateLimiter(maxRequests = max, windowSeconds = windowSecs)

            val loginLimiter = buildRateLimiter(max = 5, windowSecs = 60, prefix = "login")
            val registerLimiter = buildRateLimiter(max = 3, windowSecs = 300, prefix = "register")
            val tokenLimiter = buildRateLimiter(max = 20, windowSecs = 60, prefix = "token")
            val mfaLimiter = buildRateLimiter(max = 5, windowSecs = 300, prefix = "mfa")
            val otpEmailLimiter = buildRateLimiter(max = 3, windowSecs = 900, prefix = "otp_email")
            val otpIpLimiter = buildRateLimiter(max = 10, windowSecs = 900, prefix = "otp_ip")

            // -- Session keys (derived from KAUTH_SECRET_KEY) --------------------
            val portalSessionKey: ByteArray =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest("portal-session:${config.secretKey}".toByteArray(Charsets.UTF_8))

            val adminSessionKey: ByteArray =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest("admin-session:${config.secretKey}".toByteArray(Charsets.UTF_8))

            // -- Tenant backup/restore (v1.9.0) ------------------------------
            val backupEncryptionPort: BackupEncryptionPort = Pbkdf2AesGcmBackupEncryption()
            val backupTransactionRunner = ExposedTransactionRunner()
            val flywaySchemaVersion =
                FlywaySchemaHead.read(
                    DbConfig(
                        dbUrl = config.dbUrl,
                        dbUser = config.dbUser,
                        dbPassword = config.dbPassword,
                        dbPoolMaxSize = config.dbPoolMaxSize,
                        dbPoolMinIdle = config.dbPoolMinIdle,
                    ),
                )
            val backupExporterService =
                BackupExporterService(
                    tenantRepository = tenantRepository,
                    userRepository = userRepository,
                    applicationRepository = applicationRepository,
                    roleRepository = roleRepository,
                    groupRepository = groupRepository,
                    claimMapperRepository = tenantClaimMapperRepository,
                    identityProviderRepository = identityProviderRepository,
                    tenantKeyRepository = tenantKeyRepository,
                    userAttributeRepository = userAttributeRepository,
                    auditLogRepository = auditLogRepository,
                )
            val backupImporterService =
                BackupImporterService(
                    tenantRepository = tenantRepository,
                    userRepository = userRepository,
                    applicationRepository = applicationRepository,
                    roleRepository = roleRepository,
                    groupRepository = groupRepository,
                    claimMapperRepository = tenantClaimMapperRepository,
                    identityProviderRepository = identityProviderRepository,
                    tenantKeyRepository = tenantKeyRepository,
                    themeRepository = themeRepository,
                    portalConfigRepository = portalConfigRepository,
                    userAttributeRepository = userAttributeRepository,
                    emailBrandingRepository = emailBrandingRepository,
                    auditLogPort = auditLogAdapter,
                    transactionRunner = backupTransactionRunner,
                )

            return ServiceGraph(
                authService = authService,
                oauthService = oauthService,
                accountService = accountService,
                workspaceSettingsService = workspaceSettingsService,
                adminUserService = adminUserService,
                applicationManagementService = applicationManagementService,
                roleGroupService = roleGroupService,
                launcherService = launcherService,
                impersonationService = impersonationService,
                credentialFlowService = credentialFlowService,
                accountSelfService = accountSelfService,
                mfaService = mfaService,
                socialLoginService = socialLoginService,
                emailOtpService = emailOtpService,
                apiKeyService = apiKeyService,
                apiKeyBootstrapService = apiKeyBootstrapService,
                webhookService = webhookService,
                corsService = corsService,
                corsOriginCache = corsOriginCache,
                tenantRepository = tenantRepository,
                applicationRepository = applicationRepository,
                userRepository = userRepository,
                sessionRepository = sessionRepository,
                auditLogRepository = auditLogRepository,
                mfaRepository = mfaRepository,
                roleRepository = roleRepository,
                groupRepository = groupRepository,
                identityProviderRepository = identityProviderRepository,
                portalConfigRepository = portalConfigRepository,
                themeRepository = themeRepository,
                emailBrandingRepository = emailBrandingRepository,
                emailOtpChallengeRepository = emailOtpChallengeRepository,
                keyProvisioningService = keyProvisioning,
                portalClientProvisioning = portalClientProvisioning,
                adminClientProvisioning = adminClientProvisioning,
                adminSessionKey = adminSessionKey,
                loginRateLimiter = loginLimiter,
                registerRateLimiter = registerLimiter,
                tokenRateLimiter = tokenLimiter,
                mfaRateLimiter = mfaLimiter,
                otpEmailRateLimiter = otpEmailLimiter,
                otpIpRateLimiter = otpIpLimiter,
                portalSessionKey = portalSessionKey,
                encryptionService = encryptionService,
                socialAccountRepository = socialAccountRepository,
                keyRotationService = keyRotationService,
                tenantKeyRepository = tenantKeyRepository,
                userAttributeService = userAttributeService,
                claimMapperService = claimMapperService,
                translationPort = translationPort,
                redisClientHolder = redisClientHolder,
                applicationScope = applicationScope,
                backupExporterService = backupExporterService,
                backupImporterService = backupImporterService,
                backupEncryptionPort = backupEncryptionPort,
                auditLogPort = auditLogAdapter,
                flywaySchemaVersion = flywaySchemaVersion,
            )
        }
    }
}
