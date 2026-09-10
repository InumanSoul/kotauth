package com.kauth.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Tenant-scoped backup format v1. The full plaintext payload that gets passphrase-encrypted
 * into a `bkp1.` envelope (see BackupEncryptionPort).
 *
 * **Identity model.** No database IDs are exported. Entities are linked by their natural
 * keys (slug, username, role name, group name, clientId) so the import path can remap to
 * fresh sequence values without preserving anything from the source deployment.
 *
 * **Excluded by default.** OAuth client secrets, social-provider secrets, SMTP password,
 * MFA TOTP seeds, sessions, authorization codes, magic-link tokens — never written to a
 * backup. Operators regenerate them on the destination side; the manifest documents
 * exactly what was redacted so the runbook is self-explanatory.
 *
 * **Opt-in extras.** [signingKeys] and [auditLog] are null unless the export was invoked
 * with `--include-signing-keys` / `--include-audit-log`.
 *
 * **Forward compatibility.** [reserved] is a stable extension point for v1.10.x — TOTP
 * seed portability, scheduled-export config, etc. — so the v1 format survives those
 * additions without a major bump.
 */
@Serializable
data class BackupExportV1(
    val exportVersion: String,
    val schemaVersion: Int,
    val exportedAt: Long,
    val kotauthVersion: String,
    val manifest: ExportManifest,
    val tenant: TenantBackup,
    val applications: List<ApplicationBackup>,
    val claimMappers: List<ClaimMapperBackup>,
    val socialProviders: List<SocialProviderBackup>,
    /** Empty in backups taken before resource servers were exported (pre-1.26). */
    val resourceServers: List<ResourceServerBackup> = emptyList(),
    val users: List<UserBackup>,
    val roles: List<RoleBackup>,
    val groups: List<GroupBackup>,
    val signingKeys: List<SigningKeyBackup>? = null,
    val auditLog: List<AuditEventBackup>? = null,
    val reserved: ReservedExtensions = ReservedExtensions(),
) {
    companion object {
        /** Format identifier — bump major when restore is no longer backward-compatible. */
        const val CURRENT_EXPORT_VERSION = "1.0.0"
    }
}

/**
 * Self-describing summary of what was included or redacted in this export.
 * Operators read this first when triaging a restore — it tells them exactly which
 * secrets they need to regenerate before the destination tenant is functional.
 */
@Serializable
data class ExportManifest(
    val redactedFields: List<String>,
    val includedExtras: List<String>,
    val notes: List<String> = emptyList(),
)

@Serializable
data class TenantBackup(
    val slug: String,
    val displayName: String,
    val issuerUrl: String?,
    val tokenExpirySeconds: Long,
    val refreshTokenExpirySeconds: Long,
    val registrationEnabled: Boolean,
    val emailVerificationRequired: Boolean,
    val maxConcurrentSessions: Int?,
    val securityConfig: SecurityConfigBackup,
    val theme: ThemeBackup,
    val portalConfig: PortalConfigBackup,
    val smtp: SmtpConfigBackup,
    // Null for backups taken before email branding existed.
    val emailBranding: EmailBrandingBackup? = null,
)

@Serializable
data class EmailBrandingBackup(
    val brandName: String? = null,
    val brandColorHex: String? = null,
    val brandLogoUrl: String? = null,
    val supportEmail: String? = null,
    val fromDisplayName: String? = null,
)

@Serializable
data class SecurityConfigBackup(
    val passwordMinLength: Int,
    val passwordRequireSpecial: Boolean,
    val passwordRequireUppercase: Boolean,
    val passwordRequireNumber: Boolean,
    val passwordHistoryCount: Int,
    val passwordMaxAgeDays: Int,
    val passwordBlacklistEnabled: Boolean,
    val mfaPolicy: String,
    val lockoutMaxAttempts: Int,
    val lockoutDurationMinutes: Int,
    val corsAllowCredentials: Boolean,
    val hibpCheckEnabled: Boolean,
    val magicLinkEnabled: Boolean,
    // Defaulted so backups taken before these fields existed still import.
    val magicLinkTokenTtlMinutes: Int = 15,
    val passwordLoginEnabled: Boolean = true,
    val emailOtpSignupEnabled: Boolean = false,
    val emailOtpLockoutThreshold: Int = 5,
    val emailOtpLoginEnabled: Boolean = false,
    val loginIdentifierMode: LoginIdentifierMode = LoginIdentifierMode.USERNAME,
)

@Serializable
data class ThemeBackup(
    val accentColor: String,
    val accentHoverColor: String,
    val accentForeground: String,
    val bgDeep: String,
    val surface: String,
    val bgInput: String,
    val borderColor: String,
    val borderRadius: String,
    val textPrimary: String,
    val textMuted: String,
    val fontFamily: String,
    val logoUrl: String?,
    val faviconUrl: String?,
    val defaultLocale: String?,
    val loginLayout: String = "CENTERED",
    val loginBackgroundUrl: String? = null,
    val loginTagline: String? = null,
)

@Serializable
data class PortalConfigBackup(
    val layout: String,
)

/** SMTP password is intentionally omitted — operator must reconfigure post-restore. */
@Serializable
data class SmtpConfigBackup(
    val host: String?,
    val port: Int,
    val username: String?,
    val fromAddress: String?,
    val fromName: String?,
    val tlsEnabled: Boolean,
    val enabled: Boolean,
)

@Serializable
data class ApplicationBackup(
    val clientId: String,
    val name: String,
    val description: String?,
    val accessType: String,
    val enabled: Boolean,
    val redirectUris: List<String>,
    val tokenExpiryOverride: Int?,
    /** Null in backups written before grant types existed; the importer derives them from accessType. */
    val grantTypes: List<String>? = null,
    // All defaulted: backups taken before 1.26 carry none of these, and their defaults are the
    // same values the model itself defaults to, so an older export restores exactly as it did.
    val audience: String? = null,
    val launcherUrl: String? = null,
    val iconUrl: String? = null,
    val launcherVisible: Boolean = true,
    val launcherDisplayOrder: Int = 0,
    /** Which APIs this client may request tokens for, and which of their scopes it may ask for. */
    val authorizedResources: List<AuthorizedResourceBackup> = emptyList(),
)

/**
 * One client's grant against one resource server, keyed by the resource server's natural
 * identifier rather than its database id — the same linking rule the rest of the format uses.
 *
 * [scopes] is the per-client allowlist introduced in v1.25.0 (ADR-23), not the resource server's
 * full offering. An empty list means the client may request nothing on that resource, which is
 * what the model means by absence; it never means "everything".
 */
@Serializable
data class AuthorizedResourceBackup(
    val resourceIdentifier: String,
    val scopes: List<String> = emptyList(),
)

/**
 * A resource server (an "API" in the console). Exported by identifier so a restore can rebuild
 * both the API itself and every client authorization pointing at it.
 */
@Serializable
data class ResourceServerBackup(
    val identifier: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val scopes: List<String> = emptyList(),
)

@Serializable
data class ClaimMapperBackup(
    val attributeKey: String,
    val claimName: String,
    val includeInAccess: Boolean,
    val includeInId: Boolean,
)

/**
 * An identity provider's non-secret configuration. `clientSecret` is deliberately excluded and
 * the manifest says so; everything else a provider carries is here.
 *
 * Before 1.26 this held only provider, clientId and enabled, so a restored OIDC provider came
 * back as `oauth2` with no issuer and just-in-time provisioning silently switched off — looking
 * configured while behaving differently. The fields below are all defaulted to the model's own
 * defaults, which is exactly the shape an older backup used to restore into.
 */
@Serializable
data class SocialProviderBackup(
    val provider: String,
    val clientId: String,
    val enabled: Boolean,
    val kind: String? = null,
    val displayName: String? = null,
    val issuer: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val jwksUri: String? = null,
    val scopes: String? = null,
    val jitEnabled: Boolean = false,
    val jitAllowedDomains: List<String> = emptyList(),
    val trustEmailClaim: Boolean = false,
)

/**
 * User record. The bcrypt [passwordHash] is portable across deployments because it
 * is self-describing (algorithm + cost + salt + hash all encoded in the string).
 *
 * [mfaEnabled] is metadata only — the actual TOTP seed and recovery codes are not
 * exported. Users with MFA enabled must re-enroll on the destination tenant.
 */
@Serializable
data class UserBackup(
    val username: String,
    val email: String,
    val fullName: String,
    val passwordHash: String,
    val emailVerified: Boolean,
    val enabled: Boolean,
    val requiredActions: List<String>,
    val lastPasswordChangeAt: Long?,
    val mfaEnabled: Boolean,
    val createdAt: Long?,
    val customAttributes: Map<String, String>,
    /** Tenant-scoped role names. Client-scoped roles use `clientId/roleName`. */
    val roleNames: List<String>,
    /** Group names by qualified path: `parent/child` for nested groups. */
    val groupPaths: List<String>,
    // Null for backups taken before SCIM provisioning fields existed.
    val externalId: String? = null,
    val givenName: String? = null,
    val familyName: String? = null,
)

@Serializable
data class RoleBackup(
    val name: String,
    val description: String?,
    val scope: String,
    /** clientId of the parent application for CLIENT-scoped roles; null for TENANT-scoped. */
    val clientId: String?,
    /** Names of child roles for composite-role expansion. Same scope as the parent. */
    val childRoleNames: List<String>,
)

/**
 * Group with hierarchy expressed via [parentGroupPath] (qualified path or null for root).
 * Roles are referenced by name; CLIENT-scoped role assignments use `clientId/roleName`.
 */
@Serializable
data class GroupBackup(
    val name: String,
    val description: String?,
    val parentGroupPath: String?,
    val attributes: Map<String, String>,
    val roleNames: List<String>,
    // Null for backups taken before SCIM provisioning fields existed.
    val externalId: String? = null,
)

@Serializable
data class SigningKeyBackup(
    val keyId: String,
    val algorithm: String,
    val publicKeyPem: String,
    val privateKeyPem: String,
    val enabled: Boolean,
    val active: Boolean,
    val createdAt: Long?,
)

@Serializable
data class AuditEventBackup(
    val createdAt: Long,
    val username: String?,
    val clientId: String?,
    val eventType: String,
    val ipAddress: String?,
    val userAgent: String?,
    val details: Map<String, String>,
)

/**
 * Forward-compatibility extension point. Future versions populate these fields without
 * breaking v1 importers — unknown fields here are ignored on parse via
 * `Json { ignoreUnknownKeys = true }`.
 */
@Serializable
data class ReservedExtensions(
    val totpSeeds: JsonElement? = null,
    val scheduledExports: JsonElement? = null,
)
