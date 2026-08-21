package com.kauth.domain.service

import com.kauth.domain.model.ApplicationBackup
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEventBackup
import com.kauth.domain.model.BackupExportV1
import com.kauth.domain.model.ClaimMapperBackup
import com.kauth.domain.model.EmailBrandingBackup
import com.kauth.domain.model.ExportManifest
import com.kauth.domain.model.GroupBackup
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.PortalConfigBackup
import com.kauth.domain.model.RoleBackup
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.SecurityConfigBackup
import com.kauth.domain.model.SigningKeyBackup
import com.kauth.domain.model.SmtpConfigBackup
import com.kauth.domain.model.SocialProviderBackup
import com.kauth.domain.model.TenantBackup
import com.kauth.domain.model.ThemeBackup
import com.kauth.domain.model.UserBackup
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.TenantClaimMapperRepository
import com.kauth.domain.port.TenantKeyRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserAttributeRepository
import com.kauth.domain.port.UserRepository
import java.time.Instant

/**
 * Domain service that builds a [BackupExportV1] for a single tenant.
 *
 * Reads through every repository port, projects to the natural-key export schema,
 * redacts sensitive material, and returns a plain data structure. It does NOT
 * encrypt — that is [BackupEncryptionPort]'s job, called by the adapter that
 * persists/streams the result.
 *
 * Pure read-side: no mutations, no transactions required at this layer.
 */
class BackupExporterService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val applicationRepository: ApplicationRepository,
    private val roleRepository: RoleRepository,
    private val groupRepository: GroupRepository,
    private val claimMapperRepository: TenantClaimMapperRepository,
    private val identityProviderRepository: IdentityProviderRepository,
    private val tenantKeyRepository: TenantKeyRepository,
    private val userAttributeRepository: UserAttributeRepository,
    private val auditLogRepository: AuditLogRepository?,
) {
    /**
     * @param slug source tenant slug
     * @param options operator-controlled opt-ins for sensitive bundles (signing keys, audit log)
     * @param kotauthVersion runtime version string baked into the export
     * @param currentSchemaVersion Flyway head V-number on the source deployment
     */
    fun export(
        slug: String,
        options: ExportOptions,
        kotauthVersion: String,
        currentSchemaVersion: Int,
    ): BackupResult<BackupExportV1> {
        val tenant =
            tenantRepository.findBySlug(slug)
                ?: return BackupResult.Failure(BackupError.TenantNotFound(slug))

        val applications = applicationRepository.findByTenantId(tenant.id)
        val applicationsByPk: Map<ApplicationId, com.kauth.domain.model.Application> =
            applications.associateBy { it.id }

        val roles = roleRepository.findByTenantId(tenant.id)
        val rolesByPk: Map<RoleId, com.kauth.domain.model.Role> =
            roles.mapNotNull { role -> role.id?.let { it to role } }.toMap()
        val childMappings = roleRepository.findAllChildMappings(tenant.id)

        val groups = groupRepository.findByTenantId(tenant.id)
        val groupsByPk: Map<GroupId, com.kauth.domain.model.Group> =
            groups.mapNotNull { group -> group.id?.let { it to group } }.toMap()

        val users = userRepository.findByTenantId(tenant.id, search = null, limit = Int.MAX_VALUE, offset = 0)
        val usersByPk: Map<com.kauth.domain.model.UserId, com.kauth.domain.model.User> =
            users.mapNotNull { user -> user.id?.let { it to user } }.toMap()

        val tenantBackup =
            TenantBackup(
                slug = tenant.slug,
                displayName = tenant.displayName,
                issuerUrl = tenant.issuerUrl,
                tokenExpirySeconds = tenant.tokenExpirySeconds,
                refreshTokenExpirySeconds = tenant.refreshTokenExpirySeconds,
                registrationEnabled = tenant.registrationEnabled,
                emailVerificationRequired = tenant.emailVerificationRequired,
                maxConcurrentSessions = tenant.maxConcurrentSessions,
                securityConfig =
                    with(tenant.securityConfig) {
                        SecurityConfigBackup(
                            passwordMinLength = passwordMinLength,
                            passwordRequireSpecial = passwordRequireSpecial,
                            passwordRequireUppercase = passwordRequireUppercase,
                            passwordRequireNumber = passwordRequireNumber,
                            passwordHistoryCount = passwordHistoryCount,
                            passwordMaxAgeDays = passwordMaxAgeDays,
                            passwordBlacklistEnabled = passwordBlacklistEnabled,
                            mfaPolicy = mfaPolicy,
                            lockoutMaxAttempts = lockoutMaxAttempts,
                            lockoutDurationMinutes = lockoutDurationMinutes,
                            corsAllowCredentials = corsAllowCredentials,
                            hibpCheckEnabled = hibpCheckEnabled,
                            magicLinkEnabled = magicLinkEnabled,
                            magicLinkTokenTtlMinutes = magicLinkTokenTtlMinutes,
                            passwordLoginEnabled = passwordLoginEnabled,
                            emailOtpSignupEnabled = emailOtpSignupEnabled,
                            emailOtpLockoutThreshold = emailOtpLockoutThreshold,
                            emailOtpLoginEnabled = emailOtpLoginEnabled,
                        )
                    },
                theme =
                    with(tenant.theme) {
                        ThemeBackup(
                            accentColor = accentColor,
                            accentHoverColor = accentHoverColor,
                            accentForeground = accentForeground,
                            bgDeep = bgDeep,
                            surface = surface,
                            bgInput = bgInput,
                            borderColor = borderColor,
                            borderRadius = borderRadius,
                            textPrimary = textPrimary,
                            textMuted = textMuted,
                            fontFamily = fontFamily,
                            logoUrl = logoUrl,
                            faviconUrl = faviconUrl,
                            defaultLocale = defaultLocale,
                            loginLayout = loginLayout.name,
                            loginBackgroundUrl = loginBackgroundUrl,
                            loginTagline = loginTagline,
                        )
                    },
                portalConfig = PortalConfigBackup(layout = tenant.portalConfig.layout.name),
                smtp =
                    SmtpConfigBackup(
                        host = tenant.smtpHost,
                        port = tenant.smtpPort,
                        username = tenant.smtpUsername,
                        fromAddress = tenant.smtpFromAddress,
                        fromName = tenant.smtpFromName,
                        tlsEnabled = tenant.smtpTlsEnabled,
                        enabled = tenant.smtpEnabled,
                    ),
                emailBranding =
                    tenant.emailBranding?.let { branding ->
                        EmailBrandingBackup(
                            brandName = branding.brandName,
                            brandColorHex = branding.brandColorHex,
                            brandLogoUrl = branding.brandLogoUrl,
                            supportEmail = branding.supportEmail,
                            fromDisplayName = branding.fromDisplayName,
                        )
                    },
            )

        val applicationBackups =
            applications.map { app ->
                ApplicationBackup(
                    clientId = app.clientId,
                    name = app.name,
                    description = app.description,
                    accessType = app.accessType.value,
                    enabled = app.enabled,
                    redirectUris = app.redirectUris,
                    tokenExpiryOverride = app.tokenExpiryOverride,
                    grantTypes = app.grantTypes.map { it.value },
                )
            }

        val claimMapperBackups =
            claimMapperRepository.findAll(tenant.id).map { mapper ->
                ClaimMapperBackup(
                    attributeKey = mapper.attributeKey,
                    claimName = mapper.claimName,
                    includeInAccess = mapper.includeInAccess,
                    includeInId = mapper.includeInId,
                )
            }

        val socialProviderBackups =
            identityProviderRepository.findAllByTenant(tenant.id).map { idp ->
                SocialProviderBackup(
                    provider = idp.provider.value,
                    clientId = idp.clientId,
                    enabled = idp.enabled,
                )
            }

        val roleBackups =
            roles.map { role ->
                val childIds = childMappings[role.id] ?: emptyList()
                RoleBackup(
                    name = role.name,
                    description = role.description,
                    scope = role.scope.value,
                    clientId = role.clientId?.let { applicationsByPk[it]?.clientId },
                    childRoleNames = childIds.mapNotNull { rolesByPk[it]?.name },
                )
            }

        val groupBackups =
            groups.map { group ->
                GroupBackup(
                    name = group.name,
                    description = group.description,
                    parentGroupPath = group.parentGroupId?.let { groupPath(it, groupsByPk) },
                    attributes = group.attributes,
                    roleNames =
                        group.id?.let { gid ->
                            groupRepository.findRoleIdsForGroup(gid).mapNotNull { rolesByPk[it]?.name }
                        } ?: emptyList(),
                    externalId = group.externalId,
                )
            }

        val userBackups =
            users.map { user ->
                val userId = user.id ?: error("User ${user.username} has null id; repository invariant violated")
                val attributes = userAttributeRepository.findAll(userId, tenant.id)
                val userRoles = roleRepository.findRolesForUser(userId)
                val userGroups = groupRepository.findGroupsForUser(userId)
                UserBackup(
                    username = user.username,
                    email = user.email,
                    fullName = user.fullName,
                    passwordHash = user.passwordHash,
                    emailVerified = user.emailVerified,
                    enabled = user.enabled,
                    requiredActions = user.requiredActions.map { it.name },
                    lastPasswordChangeAt = user.lastPasswordChangeAt?.epochSecond,
                    mfaEnabled = user.mfaEnabled,
                    createdAt = user.createdAt?.epochSecond,
                    customAttributes = attributes,
                    roleNames = userRoles.map { it.name },
                    groupPaths = userGroups.mapNotNull { it.id?.let { gid -> groupPath(gid, groupsByPk) } },
                    externalId = user.externalId,
                    givenName = user.givenName,
                    familyName = user.familyName,
                )
            }

        val signingKeyBackups =
            if (options.includeSigningKeys) {
                tenantKeyRepository.findAllKeys(tenant.id).map { key ->
                    SigningKeyBackup(
                        keyId = key.keyId,
                        algorithm = key.algorithm,
                        publicKeyPem = key.publicKeyPem,
                        privateKeyPem = key.privateKeyPem,
                        enabled = key.enabled,
                        active = key.active,
                        createdAt = key.createdAt?.epochSecond,
                    )
                }
            } else {
                null
            }

        val auditLogBackups =
            if (options.includeAuditLog) {
                auditLogRepository
                    ?.findByTenant(
                        tenantId = tenant.id,
                        limit = Int.MAX_VALUE,
                        offset = 0,
                    )?.map { event ->
                        AuditEventBackup(
                            createdAt = event.createdAt.epochSecond,
                            username = event.userId?.let { usersByPk[it]?.username },
                            clientId = event.clientId?.let { applicationsByPk[it]?.clientId },
                            eventType = event.eventType.name,
                            ipAddress = event.ipAddress,
                            userAgent = event.userAgent,
                            details = event.details,
                        )
                    } ?: emptyList()
            } else {
                null
            }

        val redacted =
            mutableListOf(
                "applications.clientSecret",
                "socialProviders.clientSecret",
                "tenant.smtpPassword",
                "users.totpSeed",
                "users.recoveryCodes",
                // Passkeys / WebAuthn credentials: excluded from backups. Credentials are
                // device-bound to the original RP ID + origin; restoring on a different host
                // would break credentialId lookups. Users must re-enroll after a restore.
                // (Consistent with TOTP secret + recovery-code exclusion above.)
                "webauthn_credentials",
                "sessions",
                "authorizationCodes",
                "magicLinkTokens",
            )
        if (!options.includeSigningKeys) redacted += "signingKeys"
        if (!options.includeAuditLog) redacted += "auditLog"

        val includedExtras =
            buildList {
                if (options.includeSigningKeys) add("signingKeys")
                if (options.includeAuditLog) add("auditLog")
            }

        val export =
            BackupExportV1(
                exportVersion = BackupExportV1.CURRENT_EXPORT_VERSION,
                schemaVersion = currentSchemaVersion,
                exportedAt = Instant.now().epochSecond,
                kotauthVersion = kotauthVersion,
                manifest =
                    ExportManifest(
                        redactedFields = redacted,
                        includedExtras = includedExtras,
                        notes =
                            listOf(
                                "OAuth client secrets must be regenerated on the destination tenant.",
                                "Social-provider client secrets must be re-entered on the destination tenant.",
                                "Users with mfaEnabled=true must re-enroll MFA after import.",
                                "SMTP password must be reconfigured on the destination tenant.",
                            ),
                    ),
                tenant = tenantBackup,
                applications = applicationBackups,
                claimMappers = claimMapperBackups,
                socialProviders = socialProviderBackups,
                users = userBackups,
                roles = roleBackups,
                groups = groupBackups,
                signingKeys = signingKeyBackups,
                auditLog = auditLogBackups,
            )
        return BackupResult.Success(export)
    }

    private fun groupPath(
        id: GroupId,
        groupsByPk: Map<GroupId, com.kauth.domain.model.Group>,
    ): String {
        val segments = mutableListOf<String>()
        var cursor: GroupId? = id
        val seen = mutableSetOf<GroupId>()
        while (cursor != null && cursor !in seen) {
            seen += cursor
            val group = groupsByPk[cursor] ?: break
            segments += group.name
            cursor = group.parentGroupId
        }
        return segments.asReversed().joinToString("/")
    }
}

/** Operator-controlled opt-ins. Both default to false (security posture). */
data class ExportOptions(
    val includeSigningKeys: Boolean = false,
    val includeAuditLog: Boolean = false,
)
