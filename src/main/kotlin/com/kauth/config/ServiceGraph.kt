package com.kauth.config

import com.kauth.adapter.email.SmtpEmailAdapter
import com.kauth.adapter.persistence.PostgresApiKeyRepository
import com.kauth.adapter.persistence.PostgresApplicationRepository
import com.kauth.adapter.persistence.PostgresAuditLogAdapter
import com.kauth.adapter.persistence.PostgresPortalConfigRepository
import com.kauth.adapter.persistence.PostgresThemeRepository
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
import com.kauth.adapter.persistence.PostgresWebhookDeliveryRepository
import com.kauth.adapter.persistence.PostgresWebhookEndpointRepository
import com.kauth.adapter.social.GitHubOAuthAdapter
import com.kauth.adapter.social.GoogleOAuthAdapter
import com.kauth.adapter.token.BcryptPasswordHasher
import com.kauth.adapter.token.JwtTokenAdapter
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.MfaRepository
import com.kauth.domain.port.PortalConfigRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.ThemeRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.SocialLoginService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.domain.service.WebhookService
import com.kauth.infrastructure.DemoSeedService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.InMemoryRateLimiter
import com.kauth.infrastructure.KeyProvisioningService
import com.kauth.infrastructure.PortalClientProvisioning

/**
 * Holds every service and repository needed by the Ktor module.
 * Built once at startup by [create], then passed into the server.
 */
data class ServiceGraph(
    val authService: AuthService,
    val oauthService: OAuthService,
    val adminService: AdminService,
    val roleGroupService: RoleGroupService,
    val selfServiceService: UserSelfServiceService,
    val mfaService: MfaService,
    val socialLoginService: SocialLoginService,
    val apiKeyService: ApiKeyService,
    val webhookService: WebhookService,
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
    val keyProvisioningService: KeyProvisioningService,
    val portalClientProvisioning: PortalClientProvisioning,
    val loginRateLimiter: RateLimiterPort,
    val registerRateLimiter: RateLimiterPort,
    val tokenRateLimiter: RateLimiterPort,
    val portalSessionKey: ByteArray,
    val encryptionService: EncryptionService,
) {
    companion object {
        fun create(config: EnvironmentConfig): ServiceGraph {
            val encryptionService = EncryptionService(config.secretKey)

            // -- Repositories -------------------------------------------------
            val userRepository = PostgresUserRepository()
            val tenantRepository = PostgresTenantRepository(encryptionService)
            val applicationRepository = PostgresApplicationRepository()
            val tenantKeyRepository = PostgresTenantKeyRepository()
            val sessionRepository = PostgresSessionRepository()
            val authCodeRepository = PostgresAuthorizationCodeRepository()
            val auditLogRepository = PostgresAuditLogRepository()
            val passwordHasher = BcryptPasswordHasher()
            val evTokenRepository = PostgresEmailVerificationTokenRepository()
            val prTokenRepository = PostgresPasswordResetTokenRepository()
            val roleRepository = PostgresRoleRepository()
            val groupRepository = PostgresGroupRepository()
            val passwordPolicyAdapter = PostgresPasswordPolicyAdapter(passwordHasher)
            val identityProviderRepository =
                PostgresIdentityProviderRepository(encryptionService)
            val socialAccountRepository = PostgresSocialAccountRepository()
            val portalConfigRepository = PostgresPortalConfigRepository()
            val themeRepository = PostgresThemeRepository()
            val apiKeyRepository = PostgresApiKeyRepository()
            val webhookEndpointRepository = PostgresWebhookEndpointRepository()
            val webhookDeliveryRepository = PostgresWebhookDeliveryRepository()
            val mfaRepository = PostgresMfaRepository(encryptionService)

            // -- Key provisioning ---------------------------------------------
            val keyProvisioning =
                KeyProvisioningService(tenantRepository, tenantKeyRepository)
            keyProvisioning.provisionMissingKeys()

            val portalClientProvisioning =
                PortalClientProvisioning(
                    tenantRepository = tenantRepository,
                    applicationRepository = applicationRepository,
                    baseUrl = config.baseUrl,
                )
            portalClientProvisioning.provisionRedirectUris()

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
                )
            val auditLogAdapter =
                PostgresAuditLogAdapter(webhookService = webhookService)

            // -- Domain services ----------------------------------------------
            val emailAdapter = SmtpEmailAdapter()
            val selfServiceService =
                UserSelfServiceService(
                    userRepository = userRepository,
                    tenantRepository = tenantRepository,
                    sessionRepository = sessionRepository,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                    evTokenRepo = evTokenRepository,
                    prTokenRepo = prTokenRepository,
                    emailPort = emailAdapter,
                    passwordPolicy = passwordPolicyAdapter,
                )
            val authService =
                AuthService(
                    userRepository = userRepository,
                    tenantRepository = tenantRepository,
                    tokenPort = tokenAdapter,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                    sessionRepository = sessionRepository,
                    selfServiceService = selfServiceService,
                    passwordPolicy = passwordPolicyAdapter,
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
                )
            val adminService =
                AdminService(
                    tenantRepository = tenantRepository,
                    userRepository = userRepository,
                    applicationRepository = applicationRepository,
                    passwordHasher = passwordHasher,
                    auditLog = auditLogAdapter,
                    sessionRepository = sessionRepository,
                    selfServiceService = selfServiceService,
                    passwordPolicy = passwordPolicyAdapter,
                    themeRepository = themeRepository,
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
            val loginLimiter =
                InMemoryRateLimiter(
                    maxRequests = 5,
                    windowSeconds = 60,
                )
            val registerLimiter =
                InMemoryRateLimiter(
                    maxRequests = 3,
                    windowSeconds = 300,
                )
            val tokenLimiter =
                InMemoryRateLimiter(
                    maxRequests = 20,
                    windowSeconds = 60,
                )

            // -- Portal session key -------------------------------------------
            val portalSessionKey: ByteArray =
                run {
                    val secret = config.secretKey
                    if (!secret.isNullOrBlank()) {
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(
                                "portal-session:$secret"
                                    .toByteArray(Charsets.UTF_8),
                            )
                    } else {
                        ByteArray(32).also {
                            java.security.SecureRandom().nextBytes(it)
                        }
                    }
                }

            return ServiceGraph(
                authService = authService,
                oauthService = oauthService,
                adminService = adminService,
                roleGroupService = roleGroupService,
                selfServiceService = selfServiceService,
                mfaService = mfaService,
                socialLoginService = socialLoginService,
                apiKeyService = apiKeyService,
                webhookService = webhookService,
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
                keyProvisioningService = keyProvisioning,
                portalClientProvisioning = portalClientProvisioning,
                loginRateLimiter = loginLimiter,
                registerRateLimiter = registerLimiter,
                tokenRateLimiter = tokenLimiter,
                portalSessionKey = portalSessionKey,
                encryptionService = encryptionService,
            )
        }
    }
}
