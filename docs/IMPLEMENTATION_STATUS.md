# KotAuth — Implementation Status

> Last updated: 2026-03-16
> Current version: 1.0.0-dev (Phase 3d complete)

This document is the living record of what has been built, what compiles and runs, what is intentionally deferred, and what the next milestone requires. It supplements the strategic `ROADMAP.md`.

---

## Build Health

| Check | Status |
|---|---|
| Docker build (`gradle buildFatJar`) | ✅ Passing |
| PostgreSQL schema (Flyway V1–V16) | ✅ Applied |
| Admin console routes | ✅ All registered |
| OIDC discovery + JWKS | ✅ Functional |
| Authorization Code + PKCE flow | ✅ Functional |
| Client Credentials flow | ✅ Functional |
| Refresh token rotation | ✅ Functional |
| Email verification flow | ✅ Functional |
| Password reset flow | ✅ Functional |
| User self-service portal | ✅ Functional |
| Portal OAuth login (kotauth-portal client, PKCE) | ✅ Functional |
| Security settings page (password policy + MFA policy) | ✅ Functional |
| Auth pages display workspace name (tenant-branded) | ✅ Functional |
| Per-tenant SMTP config (admin console) | ✅ Functional |
| Role-based access control (JWT claims) | ✅ Functional |
| Group hierarchy with role inheritance | ✅ Functional |
| Password policies (history, blacklist, complexity) | ✅ Functional |
| MFA/TOTP enrollment + challenge | ✅ Functional |

---

## Phase Status

### Phase 0 — Foundation ✅ Complete

All production-readiness blockers resolved before Phase 1 work began.

| Feature | Status | Notes |
|---|---|---|
| Rate limiting on `/login` and `/register` | ✅ Done | `RateLimiter` (sliding window, in-memory, per-IP) — 5 login / 1 min, 3 register / 5 min |
| Startup validation of insecure JWT_SECRET in production | ✅ Done | `exitProcess(1)` if `KAUTH_ENV=production` and secret is default value |
| `KAUTH_BASE_URL` env var enforcement | ✅ Done | Fails fast at startup if not set |
| Input trimming and server-side validation | ✅ Done | All form handlers trim and validate before persistence |
| Refresh token persistence | ✅ Done | `sessions` table stores SHA-256 hash; rotation on every use |
| Secure cookie flags | ✅ Done | `HttpOnly` on admin session cookie |
| CSRF on admin logout | ✅ Done | POST-only logout form |
| Email verification flow | ❌ Deferred to Phase 3b | Admin-created users are pre-verified; public registration marks unverified |
| Password reset flow | ❌ Deferred to Phase 3b | Requires SMTP (not yet wired) |
| HTTPS enforcement / startup warning | ❌ Deferred to Phase 5 | TODO comment left in `Application.kt` |

---

### Phase 1 — Multi-Tenancy Core ✅ Complete

| Feature | Status | Notes |
|---|---|---|
| Flyway versioned migrations (V1–V8) | ✅ Done | Replaces `SchemaUtils`; production-safe |
| `tenants` table + master tenant bootstrapping | ✅ Done | Master tenant seeded by `V2__seed_master_tenant.sql` |
| `clients` table with `token_expiry_override` | ✅ Done | Per-client TTL override, nullable (inherits from tenant if null) |
| `redirect_uris` table (separate from clients) | ✅ Done | One-to-many, replaced atomically on client update |
| `users` scoped to `tenant_id` | ✅ Done | All queries include tenant scope; cross-tenant isolation enforced |
| Per-tenant RSA key pair for RS256 | ✅ Done | `TenantKeys` table; auto-provisioned on startup by `KeyProvisioningService` |
| Tenant CRUD in admin console | ✅ Done | Create / view / settings edit |
| Client CRUD in admin console | ✅ Done | Create / view / edit / enable-disable / secret regeneration |
| Basic user list per tenant | ✅ Done | Extended in Phase 3a |
| Admin console shell (rail + ctx-panel + top bar) | ✅ Done | Workspace switcher dropdown, rail nav, context panel |
| Admin session auth (cookie-based, 8h TTL) | ✅ Done | `AdminSession` Ktor session; auth guard via `ApplicationCallPipeline.Call` intercept |

---

### Phase 2 — OAuth 2.0 / OIDC Compliance ✅ Complete

All standard endpoints implemented. Any OIDC-compliant client library will work against KotAuth.

#### Endpoints

| Endpoint | Method | Status | Notes |
|---|---|---|---|
| `/.well-known/openid-configuration` | GET | ✅ Done | Full discovery document |
| `/protocol/openid-connect/certs` | GET | ✅ Done | JWKS with RSA public key |
| `/protocol/openid-connect/auth` | GET | ✅ Done | Authorization Code + PKCE, shows login/consent |
| `/protocol/openid-connect/token` | POST | ✅ Done | `authorization_code`, `client_credentials`, `refresh_token` grants |
| `/protocol/openid-connect/userinfo` | GET | ✅ Done | Returns standard OIDC claims for bearer token |
| `/protocol/openid-connect/logout` | POST | ✅ Done | Revokes session; supports `post_logout_redirect_uri` |
| `/protocol/openid-connect/revoke` | POST | ✅ Done | RFC 7009 token revocation |
| `/protocol/openid-connect/introspect` | POST | ✅ Done | RFC 7662 token introspection |

#### Token & Security

| Feature | Status | Notes |
|---|---|---|
| RS256 signing (asymmetric) | ✅ Done | Per-tenant key pairs; clients verify without calling KotAuth |
| Standard OIDC claims (`sub`, `iss`, `aud`, `exp`, `iat`, `jti`, `email`, `email_verified`, `name`, `preferred_username`) | ✅ Done | |
| PKCE (`S256` code challenge method) | ✅ Done | Required for public clients |
| Refresh token rotation with absolute expiry | ✅ Done | Each refresh creates a new session, revokes the old one |
| `token_expiry_override` per client | ✅ Done | Falls back to tenant default if null |
| Session persistence (`sessions` table) | ✅ Done | SHA-256 hash of access + refresh tokens |
| Audit log on all auth events | ✅ Done | `audit_log` table; append-only via `AuditLogPort`. `AuthService` records `LOGIN_SUCCESS`/`LOGIN_FAILED` for all login paths (direct, OAuth, admin console). |

#### Compile-error fixes (end of Phase 2)

The following bugs were identified and fixed before Phase 3a work began:

| Bug | File | Fix |
|---|---|---|
| `Unresolved reference: tokenExpiryOverride` | `JwtTokenAdapter.kt`, `OAuthService.kt` | Added `val tokenExpiryOverride: Int? = null` to `Application` domain model and `toApplication()` mapper |
| `Unresolved reference: origin` (wrong Ktor 2.x API) | `AuthRoutes.kt` (6 locations) | Replaced all `call.request.origin` → `call.request.local` (`RequestConnectionPoint`) |
| Cascade errors `scheme`, `serverHost`, `serverPort` | `AuthRoutes.kt` | Resolved by same fix above |

---

### Phase 3a — Admin Console (Complete) ✅ Complete

This phase delivers a fully functional admin console. Every page renders real data and all mutations go through the domain service layer.

#### Domain Layer

| Component | File | What it does |
|---|---|---|
| `AdminService` | `domain/service/AdminService.kt` | Orchestrates all admin mutations: workspace settings, user CRUD, application updates, secret rotation, session revocation. Returns `AdminResult<T>` (Success/Failure discriminated union). |
| `AdminResult<T>` / `AdminError` | same file | Sealed result types — `NotFound`, `Conflict`, `Validation`. Routes map these to appropriate HTTP status codes without leaking exceptions. |
| New `AuditEventType` variants | `domain/model/AuditEvent.kt` | `ADMIN_TENANT_UPDATED`, `ADMIN_CLIENT_UPDATED`, `ADMIN_CLIENT_SECRET_REGENERATED`, `ADMIN_CLIENT_ENABLED`, `ADMIN_CLIENT_DISABLED`, `ADMIN_USER_CREATED`, `ADMIN_USER_UPDATED`, `ADMIN_USER_ENABLED`, `ADMIN_SESSION_REVOKED` |

#### Port Extensions

| Port | New methods |
|---|---|
| `TenantRepository` | `findById(id: Int): Tenant?`, `update(tenant: Tenant): Tenant` |
| `UserRepository` | `findByTenantId(tenantId: Int, search: String?): List<User>`, `update(user: User): User` |
| `ApplicationRepository` | `update(appId, name, description, accessType, redirectUris): Application`, `setEnabled(appId, enabled)` |
| `SessionRepository` | `findActiveByTenant(tenantId: Int): List<Session>` |
| `AuditLogRepository` *(new)* | `findByTenant(tenantId, eventType?, userId?, limit, offset): List<AuditEvent>`, `countByTenant(...): Long` — read-only port, separate from write-only `AuditLogPort` |

#### Adapter Implementations

| Adapter | What was added |
|---|---|
| `PostgresTenantRepository` | `findById`, `update` (all mutable fields) |
| `PostgresUserRepository` | `findByTenantId` with optional LIKE search across username/email/fullName; `update` |
| `PostgresApplicationRepository` | `update` (atomic redirect URI replace via `deleteWhere` + `batchInsert`); `setEnabled` |
| `PostgresSessionRepository` | `findActiveByTenant` (non-revoked, non-expired, ordered by `createdAt DESC`) |
| `PostgresAuditLogRepository` *(new)* | Read-only query adapter implementing `AuditLogRepository` |

**Bug fixed:** `PostgresAuditLogRepository` line 55 — `OffsetDateTime.toInstant()` takes no arguments (the offset is embedded in the value). Incorrect call `toInstant(ZoneOffset.UTC)` was removed. This was the sole compile error blocking the Phase 3a Docker build.

**Bug fixed (post-3a):** `AuthService` had no `AuditLogPort` dependency — login events (`LOGIN_SUCCESS`, `LOGIN_FAILED`) were never recorded, making the audit log page appear empty. Fixed by adding `auditLog: AuditLogPort` to `AuthService` and recording events in `authenticate()`. All call sites (`AuthRoutes`, `AdminRoutes`) updated to pass `ipAddress` and `userAgent`. `AdminRoutes` admin login now calls `authenticate()` instead of `login()` so master-tenant logins are audited too.

#### Admin Routes

All routes registered under `/admin/workspaces/{slug}/`:

| Route | Method | Handler |
|---|---|---|
| `/settings` | GET | Show workspace settings form |
| `/settings` | POST | Update workspace settings via `AdminService` |
| `/applications/{clientId}/edit` | GET | Show edit application form |
| `/applications/{clientId}/edit` | POST | Update application via `AdminService` |
| `/applications/{clientId}/toggle` | POST | Enable/disable application |
| `/applications/{clientId}/regenerate-secret` | POST | Regenerate client secret; new secret shown once via `?newSecret=` query param |
| `/users` | GET | User list with optional `?q=` search |
| `/users/new` | GET | Show create user form |
| `/users` | POST | Create user via `AdminService` (admin-created = pre-verified) |
| `/users/{userId}` | GET | User detail with inline edit form and active sessions |
| `/users/{userId}/toggle` | POST | Enable/disable user |
| `/users/{userId}/edit` | POST | Update user profile via `AdminService` |
| `/users/{userId}/revoke-sessions` | POST | Revoke all user sessions |
| `/sessions` | GET | Workspace-wide active session list |
| `/sessions/{sessionId}/revoke` | POST | Revoke one session |
| `/logs` | GET | Audit log with event-type filter and pagination (50/page) |

#### Admin Views

New page functions added to `AdminView.kt`:

| Function | Page |
|---|---|
| `workspaceSettingsPage` | Settings form: identity, token TTLs, password policy, branding |
| `editApplicationPage` | Edit form with locked client ID, access type, redirect URIs |
| `userListPage` | Searchable user table with active/disabled status badges |
| `createUserPage` | Create user form (username / email / full name / password) |
| `userDetailPage` | Profile edit form + active sessions table + enable/disable + revoke-all header actions |
| `activeSessionsPage` | Workspace-wide session table with per-row revoke |
| `auditLogPage` | Paginated event log with event-type dropdown filter |

`applicationDetailPage` updated to accept `newSecret: String?` — renders a one-time banner prompting the admin to copy the new client secret.

New data class: `UserPrefill` — holds create-user form values for re-population after a failed submission (mirrors `WorkspacePrefill` and `ApplicationPrefill`).

---

### Phase 3b — User Self-Service & Email Flows ✅ Complete

All user-facing email and self-service features are implemented.

#### Domain Layer

| Component | File | What it does |
|---|---|---|
| `UserSelfServiceService` | `domain/service/UserSelfServiceService.kt` | Email verification, forgot password, profile update, password change, session listing and self-revocation. Returns `SelfServiceResult<T>`. |
| `SelfServiceResult<T>` / `SelfServiceError` | same file | Discriminated union mirroring `AdminResult`. Error subtypes: `NotFound`, `Validation`, `Unauthorized`, `TokenExpired`, `TokenInvalid`, `SmtpNotConfigured`, `EmailDeliveryFailed`. |
| `EmailVerificationToken` | `domain/model/EmailVerificationToken.kt` | Token model with `isValid`, `isExpired`, `isUsed` computed properties. 24-hour expiry. |
| `PasswordResetToken` | `domain/model/PasswordResetToken.kt` | Token model with ip_address. 1-hour expiry. |
| `EncryptionService` | `infrastructure/EncryptionService.kt` | AES-256-GCM symmetric encryption for SMTP passwords. Key derived via SHA-256 from `KAUTH_SECRET_KEY` env var. Returns `null` on decrypt failure — never throws. |

#### Port Extensions

| Port | New methods |
|---|---|
| `UserRepository` | `updatePassword(userId, passwordHash, changedAt): User`, `findByEmail(tenantId, email): User?` |
| `SessionRepository` | `countActiveByUser(tenantId, userId): Int`, `revokeOldestForUser(tenantId, userId, keepNewest: Int)`, `findById(id): Session?` |
| `EmailPort` *(new)* | `sendVerificationEmail(to, toName, verifyUrl, workspaceName, tenant)`, `sendPasswordResetEmail(to, toName, resetUrl, workspaceName, tenant)` |
| `EmailVerificationTokenRepository` *(new)* | `create`, `findByTokenHash`, `markUsed(tokenId, usedAt)`, `deleteUnusedByUser(userId)` |
| `PasswordResetTokenRepository` *(new)* | `create`, `findByTokenHash`, `markUsed(tokenId, usedAt)`, `deleteByUser(userId)` |

#### Database Schema (Flyway V9–V11)

| Migration | What it adds |
|---|---|
| `V9__user_lifecycle_config.sql` | SMTP columns on `tenants` (host, port, username, password, from_address, from_name, tls_enabled, enabled, max_concurrent_sessions); `last_password_change_at` on `users` |
| `V10__email_verification_tokens.sql` | `email_verification_tokens` table with CASCADE deletes and index on `token_hash` |
| `V11__password_reset_tokens.sql` | `password_reset_tokens` table with `ip_address` column and CASCADE deletes |

#### Auth Routes (Phase 3b additions)

All routes under `/t/{slug}/`:

| Route | Method | Handler |
|---|---|---|
| `/forgot-password` | GET | Show forgot-password form |
| `/forgot-password` | POST | Call `initiateForgotPassword`; always redirect to `?sent=true` (no enumeration) |
| `/reset-password` | GET | Show reset form with token from query param |
| `/reset-password` | POST | Call `confirmPasswordReset`; show success or inline error |
| `/verify-email` | GET | Call `confirmEmailVerification`; show success or error page |

Login page updated: "Forgot password?" link added above the register link.

#### Self-Service Portal Routes

All routes under `/t/{slug}/account/`:

| Route | Method | Handler |
|---|---|---|
| `/login` | GET | Show portal login page |
| `/login` | POST | Authenticate via `AuthService`, set `PortalSession` cookie |
| `/logout` | POST | Clear portal cookie, redirect to login |
| `/profile` | GET | Show profile page (guarded by `PortalSession`) |
| `/profile` | POST | Call `updateProfile`; show success/error inline |
| `/security` | GET | Show password change form + active sessions table |
| `/change-password` | POST | Call `changePassword`; on success: clear cookie, redirect to login with message |
| `/sessions/{sessionId}/revoke` | POST | Call `revokeSession`; redirect back to security page |

#### Admin Routes (Phase 3b additions)

| Route | Method | Handler |
|---|---|---|
| `/admin/workspaces/{slug}/settings/smtp` | GET | Show SMTP config form |
| `/admin/workspaces/{slug}/settings/smtp` | POST | Call `AdminService.updateSmtpConfig` |
| `/admin/workspaces/{slug}/users/{userId}/send-verification` | POST | Call `AdminService.resendVerificationEmail` |
| `/admin/workspaces/{slug}/users/{userId}/admin-reset-password` | POST | Call `AdminService.adminResetUserPassword` (force-sets password, revokes all sessions) |

#### Admin Console Updates

- `userDetailPage`: new "Resend Verification Email" button (shown only when `!user.emailVerified && workspace.isSmtpReady`); new "Admin Password Reset" form section
- `workspaceSettingsPage`: "SMTP Settings →" link in form actions
- `smtpSettingsPage` (new): SMTP configuration form with enable toggle, server/auth/sender sections; blank password = keep existing

#### Architectural Decisions (Phase 3b)

**ADR-07: Portal Session — Cookie-Based (KISS, Phase 3b)**

The self-service portal uses a Ktor cookie session (`PortalSession`) rather than requiring users to complete a full OAuth Authorization Code flow to access their own profile. This avoids circular dependency: the portal is part of the auth platform, not an external app. The session is HMAC-signed with a key derived from `KAUTH_SECRET_KEY`. Upgrade path: Phase 5 can replace the portal with a first-party OAuth client (the portal authenticates against its own tenant and uses access tokens, same as any other app). See `PortalSession.kt` for full decision doc.

**ADR-08: Email Templates — Plain HTML, Workspace Name Only**

Email templates use a minimal plain white layout with the workspace display name as header. No theme colors or tenant branding. Decision rationale: email client CSS support is unreliable; the workspace name provides sufficient context without risking broken renders in diverse mail clients.

**ADR-09: Forgot Password Always Returns Success**

`UserSelfServiceService.initiateForgotPassword` always returns `SelfServiceResult.Success` regardless of whether the email exists, the user is disabled, or SMTP is misconfigured. This is the only correct security posture — enumeration of registered emails is a meaningful information leak.

**ADR-10: SMTP Password Encryption at Rest**

SMTP passwords are encrypted with AES-256-GCM before storage (`EncryptionService`). The 256-bit key is derived deterministically from `KAUTH_SECRET_KEY` via SHA-256, so the key does not need to be stored separately. Ciphertext format: `base64url(iv).base64url(ciphertext+auth_tag)`. If `KAUTH_SECRET_KEY` is not set, SMTP is silently unavailable but the application starts normally.

---

### Phase 3c — Access Control, Password Policies & MFA ✅ Complete

Phase 3 is now fully complete. This sub-phase delivers roles, groups, expanded password policies, and TOTP-based multi-factor authentication.

#### Database Schema (Flyway V12–V14)

| Migration | What it adds |
|---|---|
| `V12__roles_and_groups.sql` | `roles` table (tenant + client scope, CHECK constraint), `composite_role_mappings`, `user_roles`, `groups` (self-referencing hierarchy), `group_roles`, `user_groups`. Indexes on all foreign keys. |
| `V13__password_policies.sql` | `password_history` table (BCrypt hashes of previous N passwords), `password_blacklist` table seeded with 50 common passwords. 5 new columns on `tenants`: `password_policy_history_count`, `password_policy_max_age_days`, `password_policy_require_uppercase`, `password_policy_require_number`, `password_policy_blacklist_enabled`. |
| `V14__mfa_totp.sql` | `mfa_enrollments` table (TOTP secret, verified/enabled state), `mfa_recovery_codes` table (BCrypt-hashed one-time codes). `mfa_policy` column on `tenants` (optional/required/required_admins). `mfa_enabled` column on `users`. |

#### Domain Layer — Roles & Groups

| Component | File | What it does |
|---|---|---|
| `Role` | `domain/model/Role.kt` | Domain model with `RoleScope` enum (TENANT/CLIENT), composite role tracking via `childRoleIds` |
| `Group` | `domain/model/Group.kt` | Hierarchical group model with `parentGroupId` self-reference and JSONB `attributes` |
| `RoleRepository` | `domain/port/RoleRepository.kt` | Port with CRUD, composite role management, user-role assignment, and `resolveEffectiveRoles` (aggregates direct + group + composite roles) |
| `GroupRepository` | `domain/port/GroupRepository.kt` | Port with CRUD, role assignment, user membership, `findAncestorGroupIds` for hierarchy traversal |
| `RoleGroupService` | `domain/service/RoleGroupService.kt` | Combined domain service (450+ lines). Role CRUD with name regex validation, scope/clientId consistency, uniqueness. Composite role management with BFS cycle detection. Group CRUD with parent validation and sibling uniqueness. All mutations audit-logged. |

#### Domain Layer — Password Policies

| Component | File | What it does |
|---|---|---|
| `PasswordPolicyPort` | `domain/port/PasswordPolicyPort.kt` | Port with `validate`, `recordPasswordHistory`, `isInHistory`, `isBlacklisted` |
| `PostgresPasswordPolicyAdapter` | `adapter/persistence/PostgresPasswordPolicyAdapter.kt` | Validates complexity (length, uppercase, numbers, special chars), checks password history (BCrypt verify against last N hashes), blacklist lookup (global + tenant-specific) |

#### Domain Layer — MFA/TOTP

| Component | File | What it does |
|---|---|---|
| `MfaEnrollment` | `domain/model/MfaEnrollment.kt` | Domain model with `MfaMethod` enum (TOTP, extensible). Secret field encrypted at rest via `EncryptionService`. |
| `MfaRecoveryCode` | `domain/model/MfaRecoveryCode.kt` | One-time backup code (BCrypt-hashed, `usedAt` set when consumed) |
| `MfaRepository` | `domain/port/MfaRepository.kt` | Port for enrollment CRUD and recovery code management |
| `TotpUtil` | `infrastructure/TotpUtil.kt` | Zero-dependency RFC 6238 TOTP implementation. HMAC-SHA1, 6-digit codes, 30s period, ±1 time step window. Base32 encode/decode (RFC 4648). Generates `otpauth://` URIs for QR code scanning. |
| `MfaService` | `domain/service/MfaService.kt` | Enrollment workflow (generate secret → QR URI → recovery codes), enrollment verification, TOTP challenge during login, recovery code verification (consumed on use), disable MFA. Returns `MfaResult<T>` sealed type. |

#### Persistence Adapters

| Adapter | What was added |
|---|---|
| `PostgresRoleRepository` | Full `RoleRepository` implementation. `resolveEffectiveRoles` aggregates: direct user roles + group roles (including ancestor groups via BFS traversal) + composite role expansion (BFS with visited set for cycle safety). |
| `PostgresGroupRepository` | Full `GroupRepository` implementation. JSONB attribute serialization via `kotlinx.serialization.json`. Ancestor group traversal for role inheritance. |
| `PostgresPasswordPolicyAdapter` | Implements `PasswordPolicyPort`. History check verifies BCrypt against last N hashes. Blacklist normalizes to lowercase, checks both global (tenant_id IS NULL) and tenant-specific entries. |
| `PostgresMfaRepository` | Full `MfaRepository` implementation. TOTP secrets encrypted at rest via `EncryptionService` (AES-256-GCM). Recovery codes stored as BCrypt hashes. |
| `PostgresUserRepository` | Updated: `update()` now writes `mfaEnabled`; `toUser()` reads `mfaEnabled` |
| `PostgresTenantRepository` | Updated: reads/writes `mfaPolicy` and 5 password policy columns |

#### Token Claims — Keycloak-Compatible Role Embedding

JWT access tokens now include role claims following the Keycloak convention:

```json
{
  "realm_access": { "roles": ["admin", "user"] },
  "resource_access": {
    "my-app": { "roles": ["editor", "viewer"] }
  }
}
```

`JwtTokenAdapter.issueUserTokens` accepts a `roles: List<Role>` parameter. Tenant-scoped roles go into `realm_access.roles`; client-scoped roles are keyed by clientId (int-as-string for MVP) under `resource_access`. `AccessTokenClaims` extended with `realmRoles` and `resourceRoles` fields. `OAuthService.exchangeAuthorizationCode` and `refreshTokens` both resolve effective roles via `RoleRepository.resolveEffectiveRoles` before token issuance.

#### Admin Routes (Phase 3c additions)

All routes under `/admin/workspaces/{slug}/`:

| Route | Method | Handler |
|---|---|---|
| `/roles` | GET | List all roles (JSON) |
| `/roles` | POST | Create role |
| `/roles/{roleId}/edit` | POST | Update role |
| `/roles/{roleId}/delete` | POST | Delete role |
| `/roles/{roleId}/children` | POST | Add composite child role (with cycle detection) |
| `/roles/{roleId}/assign-user` | POST | Assign role to user |
| `/roles/{roleId}/unassign-user` | POST | Unassign role from user |
| `/groups` | GET | List all groups (JSON) |
| `/groups` | POST | Create group |
| `/groups/{groupId}/edit` | POST | Update group |
| `/groups/{groupId}/delete` | POST | Delete group |
| `/groups/{groupId}/assign-role` | POST | Assign role to group |
| `/groups/{groupId}/add-member` | POST | Add user to group |
| `/groups/{groupId}/remove-member` | POST | Remove user from group |

#### Auth Routes (Phase 3c additions)

| Route | Method | Handler |
|---|---|---|
| `/t/{slug}/mfa-challenge` | GET | Show MFA challenge page (TOTP code entry) |
| `/t/{slug}/mfa-challenge` | POST | Verify TOTP code or recovery code; complete login flow |

MFA challenge inserts between password authentication and token issuance. A `KOTAUTH_MFA_PENDING` cookie (5-minute TTL, httpOnly) carries the userId across the redirect. OAuth2 parameters are preserved through the MFA step via hidden form fields.

#### Self-Service Portal Routes (Phase 3c additions)

| Route | Method | Handler |
|---|---|---|
| `/t/{slug}/account/mfa/enroll` | POST | Start TOTP enrollment; returns `totp_uri` + `recovery_codes` (JSON) |
| `/t/{slug}/account/mfa/verify` | POST | Confirm enrollment with TOTP code from authenticator app |
| `/t/{slug}/account/mfa/disable` | POST | Remove MFA enrollment and recovery codes |

#### Audit Events (Phase 3c additions)

18 new `AuditEventType` variants:

Roles & Groups: `ADMIN_ROLE_CREATED`, `ADMIN_ROLE_UPDATED`, `ADMIN_ROLE_DELETED`, `ADMIN_ROLE_ASSIGNED`, `ADMIN_ROLE_UNASSIGNED`, `ADMIN_GROUP_CREATED`, `ADMIN_GROUP_UPDATED`, `ADMIN_GROUP_DELETED`, `ADMIN_GROUP_ROLE_ASSIGNED`, `ADMIN_GROUP_ROLE_UNASSIGNED`, `ADMIN_GROUP_MEMBER_ADDED`, `ADMIN_GROUP_MEMBER_REMOVED`

MFA: `MFA_ENROLLMENT_STARTED`, `MFA_ENROLLMENT_VERIFIED`, `MFA_CHALLENGE_SUCCESS`, `MFA_CHALLENGE_FAILED`, `MFA_RECOVERY_CODE_USED`, `MFA_DISABLED`

#### Architectural Decisions (Phase 3c)

**ADR-11: Keycloak-Compatible JWT Role Claims**

**Decision:** Role information is embedded in JWT access tokens using the `realm_access.roles` and `resource_access.{clientId}.roles` claim structure, matching Keycloak's convention.

**Consequence:** Any application, SDK, or middleware already integrated with Keycloak (Spring Security Keycloak adapter, NextAuth.js, Kong JWT plugin, etc.) can consume KotAuth tokens with zero client-side code changes. The cost is two JSON keys; the benefit is ecosystem compatibility. A future tenant-level `tokenClaimFormat` config could support alternative formats if needed.

**ADR-12: BFS Composite Role Expansion with Cycle Detection**

**Decision:** Composite roles (a role that includes other roles) are expanded at token-issuance time using breadth-first search with a visited set. Before adding a child role, `RoleGroupService.wouldCreateCycle` runs a BFS from the proposed child to detect if adding it would create a circular dependency.

**Consequence:** Composite roles can be nested arbitrarily deep without risk of infinite loops. The expansion is deterministic and O(n) in the number of role-role edges.

**ADR-13: Zero-Dependency TOTP (RFC 6238)**

**Decision:** TOTP verification is implemented in `TotpUtil.kt` using only `javax.crypto.Mac` (HMAC-SHA1) and `java.security.SecureRandom`. No external TOTP library dependency.

**Consequence:** No additional dependency in `build.gradle.kts`. The implementation covers Google Authenticator defaults (SHA1, 6 digits, 30s period, ±1 window). If future MFA methods (WebAuthn, FIDO2) require external libraries, they can be added independently without affecting TOTP.

**ADR-14: MFA Challenge via Cookie-Based Pending State**

**Decision:** After successful password authentication, if MFA is required, the server sets a `KOTAUTH_MFA_PENDING` cookie containing `userId|slug|timestamp` and redirects to `/mfa-challenge`. The cookie has a 5-minute TTL and `httpOnly` flag. OAuth2 parameters are preserved via hidden form fields.

**Consequence:** The MFA step is stateless on the server side (no server-side pending-MFA session table). The trade-off is that the cookie value is not signed or encrypted (MVP limitation) — a malicious actor with cookie access could forge a pending MFA state. Phase 5 should upgrade this to a signed/encrypted token or server-side session.

---

---

### Phase 3d — Portal OAuth, Settings Hardening & Auth Branding ✅ Complete

This sub-phase closes several gaps discovered after Phase 3c shipped: the self-service portal now authenticates via the same standard OAuth Authorization Code + PKCE flow as any external application, the admin settings page is split into logical sections, and all tenant-facing auth pages carry the tenant's display name rather than a hardcoded "KotAuth" string.

#### Database Schema (Flyway V15–V16)

| Migration | What it adds |
|---|---|
| `V15__portal_client.sql` | Seeds one `kotauth-portal` PUBLIC client row per tenant. Client is disabled for the master tenant (master users use the admin console, not the self-service portal). Redirect URIs are NOT set here — they depend on `KAUTH_BASE_URL` and are upserted at runtime by `PortalClientProvisioning`. |
| `V16__default_roles.sql` | Seeds two tenant-scoped baseline roles (`admin`, `user`) for all existing tenants. Idempotent via `ON CONFLICT DO NOTHING`. New tenants created after this migration receive defaults via application logic. |

#### Infrastructure — Portal Client Provisioning

| Component | File | What it does |
|---|---|---|
| `PortalClientProvisioning` | `infrastructure/PortalClientProvisioning.kt` | Startup service called from `Application.kt` after Flyway runs. Iterates all non-master tenants; if the `kotauth-portal` client row is missing (tenant created after V15 ran), it creates it. If the redirect URI differs from the live `KAUTH_BASE_URL`, it updates. Fully idempotent — safe to run on every deployment. |

**Why this is necessary:** V15 only seeds portal clients for tenants that exist at migration time. `PortalClientProvisioning` bridges the gap for tenants created afterward and also auto-corrects redirect URIs when `KAUTH_BASE_URL` changes between deployments.

#### Admin Settings Page Split

The workspace settings page was split into two logically separate pages to reduce cognitive load. Previously `/settings` held all 12+ fields on a single form.

| Route | Method | Section |
|---|---|---|
| `/admin/workspaces/{slug}/settings` | GET/POST | General settings: Identity (display name, slug, registration, email verification), Token Lifetimes, Registration Policy, Branding (primary color, logo URL) |
| `/admin/workspaces/{slug}/settings/security` | GET/POST | Security Policy: Password Policy (min length, uppercase, numbers, special chars, history count, max age, blacklist), MFA Policy (optional / required / required for admins) |

Each POST handler reads only the fields it owns from the submitted form and preserves all other fields from the current workspace state — so saving general settings never overwrites security policy configuration, and vice versa.

A "Security Policy →" ghost-button link in the general settings form actions navigates to the security page. Both pages highlight the correct sidenav item (`activeAppSection = "general"` vs `"security"`).

#### Workspace Detail — Portal Quick-Access Button

`workspaceDetailPage` now renders an "Open Portal ↗" button alongside the existing "Open Login ↗" button. The portal button links to `/t/{slug}/account/login`, which is the OAuth-initiated portal login endpoint.

#### Auth Pages — Tenant Workspace Name

All six auth page functions in `AuthView.kt` previously hardcoded the string `"KotAuth"` as the brand name displayed in the page header, title, and logo `alt` attribute.

| Change | Detail |
|---|---|
| `AuthView.kt` — all 6 page functions | Added `workspaceName: String = "KotAuth"` parameter; replaced every `+"KotAuth"` and `"KotAuth | …"` occurrence with the dynamic value |
| `AuthRoutes.kt` — all ~15 call sites | Updated all handlers to extract `val workspaceName = tenant?.displayName ?: "KotAuth"` and pass it as the third positional argument |

Affected pages: `loginPage`, `registerPage`, `forgotPasswordPage`, `resetPasswordPage`, `verifyEmailPage`, `mfaChallengePage`.

The OAuth authorization endpoint (`GET /protocol/openid-connect/auth`) is also updated — this is the entry point for portal logins and was the highest-priority call site.

#### Build Fix — `continue` Inside Inline Lambda

The initial implementation of `PortalClientProvisioning.provisionRedirectUris()` used `?: run { ...; continue }` to skip loop iterations. This triggers Kotlin's experimental `"break continue in inline lambdas"` feature flag. Fixed by replacing the `run {}` block with an explicit `if (portalClient == null) { ... } else if (...) { ... }` pattern — `continue` at the `for` loop level is standard Kotlin and requires no flags.

---

### Phase 4 — Identity Federation (Social Login) ✅ Complete

Google and GitHub OAuth2 social login is fully implemented and wired. The foundation is in place for adding a third generic OIDC provider without new patterns.

#### Database Schema (Flyway V17–V18)

| Migration | What it adds |
|---|---|
| `V17__identity_providers.sql` | Per-tenant OAuth2 provider config: `(id, tenant_id, provider VARCHAR(32), client_id, client_secret_encrypted, enabled, created_at, updated_at)`. Unique on `(tenant_id, provider)`. `client_secret` AES-256-GCM encrypted at rest via `EncryptionService`. |
| `V18__social_accounts.sql` | Bridge table linking provider identity to local user: `(id, user_id FK, tenant_id FK, provider, provider_user_id, provider_email, provider_name, avatar_url, linked_at)`. Two UNIQUE constraints: `(provider, provider_user_id)` prevents double-linking a provider account; `(tenant_id, user_id, provider)` prevents one user having multiple links per provider. Indexes on `user_id` and `(tenant_id, provider, provider_user_id)`. |

#### Domain Layer

| Component | File | What it does |
|---|---|---|
| `SocialProvider` | `domain/model/IdentityProvider.kt` | Enum with `GOOGLE("google", "Google")` and `GITHUB("github", "GitHub")`; `fromValue()` and `fromValueOrNull()` companion methods |
| `IdentityProvider` | `domain/model/IdentityProvider.kt` | Domain model with `clientSecret` as plaintext at runtime (decrypted on load from DB) |
| `SocialAccount` | `domain/model/SocialAccount.kt` | Links `userId`, `tenantId`, `provider`, `providerUserId`, `providerEmail`, `providerName`, `avatarUrl`, `linkedAt` |
| `SocialProviderPort` | `domain/port/SocialProviderPort.kt` | Interface: `provider: SocialProvider`, `exchangeCodeForProfile(code, redirectUri, clientId, clientSecret): SocialUserProfile`, `buildAuthorizationUrl(clientId, redirectUri, state, scopes): String`. Data classes: `SocialTokenResponse`, `SocialUserProfile(providerUserId, email, name, emailVerified, avatarUrl)` |
| `IdentityProviderRepository` | `domain/port/IdentityProviderRepository.kt` | Port: `findEnabledByTenant`, `findAllByTenant`, `findByTenantAndProvider`, `save`, `update`, `delete` |
| `SocialAccountRepository` | `domain/port/SocialAccountRepository.kt` | Port: `findByProviderIdentity`, `findByUserId`, `save`, `delete` |
| `SocialLoginService` | `domain/service/SocialLoginService.kt` | Orchestrates the full OAuth callback flow. Three-step account resolution: (1) existing social_account link → reuse user; (2) email match in same tenant → auto-link; (3) new user → create with random unusable password hash. Issues tokens and records audit events. Returns `SocialLoginResult<T>` sealed type (`SocialLoginSuccess`, `SocialLoginError`). |

#### Provider Adapters

| Adapter | File | Notes |
|---|---|---|
| `GoogleOAuthAdapter` | `adapter/social/GoogleOAuthAdapter.kt` | Authorization: `accounts.google.com/o/oauth2/v2/auth`. Token: `oauth2.googleapis.com/token`. Profile: `openidconnect.googleapis.com/v1/userinfo`. Scopes: `openid email profile`. Adds `access_type=online&prompt=select_account`. |
| `GitHubOAuthAdapter` | `adapter/social/GitHubOAuthAdapter.kt` | Authorization: `github.com/login/oauth/authorize`. Token: `github.com/login/oauth/access_token`. Profile: `api.github.com/user`. Scopes: `read:user user:email`. Falls back to `api.github.com/user/emails` for users with private email. Accept header: `application/vnd.github+json`. |

Both adapters use `java.net.http.HttpClient` (JDK 11+, no new Gradle dependency) and `kotlinx.serialization.json` (already on classpath via Ktor) for JSON parsing. Internal helpers `toQueryString()`, `toFormBody()`, `urlEncode()` are package-private within `adapter/social/`.

#### Persistence Adapters

| Adapter | File | Notes |
|---|---|---|
| `PostgresIdentityProviderRepository` | `adapter/persistence/PostgresIdentityProviderRepository.kt` | Encrypts `clientSecret` via `EncryptionService.encrypt()` on write; decrypts on read. Returns `null` (skips row) if decryption fails — no crash on bad key. Uses `OffsetDateTime.now(ZoneOffset.UTC)` for writes, `.toInstant()` for reads (consistent with all existing adapters). |
| `PostgresSocialAccountRepository` | `adapter/persistence/PostgresSocialAccountRepository.kt` | Uses `OffsetDateTime.now(ZoneOffset.UTC)` for `linkedAt` write; `.toInstant()` on read. |

Exposed ORM tables: `IdentityProvidersTable.kt`, `SocialAccountsTable.kt` — both use `timestampWithTimeZone` (maps to `OffsetDateTime`) matching the `TIMESTAMPTZ` schema columns and all existing table definitions.

#### Auth Routes (Phase 4 additions)

| Route | Method | Handler |
|---|---|---|
| `/t/{slug}/auth/social/{provider}/redirect` | GET | Loads IDP config; constructs HMAC-signed state = `EncryptionService.signCookie("${provider}|${slug}|${nonce}|${oauthParamsB64}")`; redirects to provider authorization URL. OAuth params encoded as Base64 in state — no extra cookie or server-side session needed. |
| `/t/{slug}/auth/social/{provider}/callback` | GET | Verifies HMAC state signature; decodes OAuth params from state; calls `SocialLoginService.handleCallback()`; supports both full OAuth2 Code Flow (issues auth code → redirect to client) and direct flow (returns JSON tokens). |

`GET /t/{slug}/login` updated to load enabled providers via `IdentityProviderRepository.findEnabledByTenant()` and pass them to `AuthView.loginPage(enabledProviders = ...)`.

Added helpers in `AuthRoutes.kt`: `SocialLoginError.toMessage()` extension function; `parseQueryStringToOAuthParams(qs)` to restore OAuth params from the state's Base64 segment.

#### Admin Routes (Phase 4 additions)

| Route | Method | Handler |
|---|---|---|
| `/admin/workspaces/{slug}/settings/identity-providers` | GET | Lists all providers with current config status; supports `?edit={provider}` for inline form. |
| `/admin/workspaces/{slug}/settings/identity-providers/{provider}` | POST | Creates or updates IDP config. Validates clientId non-empty; checks `EncryptionService.isAvailable` before saving secret. Preserves existing encrypted secret if the form secret field is left blank. |
| `/admin/workspaces/{slug}/settings/identity-providers/{provider}/delete` | POST | Deletes provider config row. |

#### Admin & Auth Views (Phase 4 additions)

- `AdminView.kt`: `identityProvidersPage()` — lists all `SocialProvider.entries`; shows configure/edit/delete per provider; displays callback URI per provider for easy copy-paste into Google/GitHub consoles; inline edit form with clientId/clientSecret/enabled fields; confirm dialog for delete.
- `AdminView.kt`: `renderSettingsCtxPanel()` — added `"identity-providers"` link in the settings sidebar.
- `AuthView.kt`: `loginPage()` — new `enabledProviders: List<SocialProvider>` parameter; renders `social-divider` + `social-buttons` below footer links; Google uses inline multicolor SVG logo; GitHub uses inline monochrome SVG.

#### Auth & Admin CSS (Phase 4 additions)

New classes added to `kotauth-auth.css`:

| Class | Purpose |
|---|---|
| `.social-divider` | "or continue with" separator; `::before`/`::after` pseudo-elements draw full-width horizontal rules |
| `.social-buttons` | Flex column with `gap: 0.6rem` |
| `.btn-social` | Ghost button style — `transparent` background, `1px solid var(--border)`, full width, hover brings `var(--bg-input)` fill. Overrides `.btn` defaults (`margin-top: 0`, `letter-spacing: normal`). |
| `.social-icon` | Inline SVG container — `display: flex; align-items: center; line-height: 0` prevents phantom vertical space from inline SVG |

#### Architectural Decisions (Phase 4)

**ADR-15: CSRF State Without Extra Cookies**

The OAuth `state` parameter carries the CSRF nonce AND all OAuth authorization params encoded as Base64, signed with `EncryptionService.signCookie()` (HMAC-SHA256). No server-side session or extra cookie is required to thread OAuth params through the social login flow. If the HMAC doesn't verify on callback, the request is rejected with `400 Bad Request`.

**ADR-16: Email-Based Account Auto-Linking**

When a social login arrives and no existing `social_accounts` row matches, the service checks for a user with the same email in the same tenant. If found, the social account is linked automatically without a confirmation step. Rationale: same email within the same tenant nearly always means the same person, and a confirmation step creates friction for the most common case. A tenant-level policy toggle is the right future upgrade path if stricter confirmation is required.

**ADR-17: No New Gradle Dependencies for Provider HTTP**

Both provider adapters use `java.net.http.HttpClient` (JDK 11+, available on the Java 17 runtime) for outbound HTTP and `kotlinx.serialization.json` (already on the classpath via Ktor) for JSON parsing. Zero new Gradle entries — keeps the fat JAR lean and the dependency surface minimal.

**ADR-18: New Social Users Get an Unusable Password Hash**

Users created via social login have their `passwordHash` set to a random 64-char hex string (BCrypt-hashed). This is not a valid password any real user could produce — it ensures the account exists in the user table with a plausible `passwordHash` column value, while making password-based login impossible for that account until the user explicitly sets a password via the self-service portal.

---

### Phase 5 — Admin REST API ❌ Not started

Full CRUD REST API (`/api/v1/`), API keys scoped per tenant, OpenAPI spec, webhook events.

---

### Phase 6 — Documentation ❌ Not started

README, CONTRIBUTING, integration guides (Next.js, generic OIDC), API quick reference.

---

## Architecture Decisions (Recorded)

### ADR-01: Hexagonal (Ports & Adapters) Architecture

**Decision:** All domain logic in `domain/` has zero framework dependencies. Adapters in `adapter/` implement domain ports. The composition root (`Application.kt`) wires everything.

**Consequence:** Adding a new persistence layer (e.g., MySQL) means writing a new adapter, not touching domain logic. Tested domain services with no Ktor/Exposed imports.

### ADR-02: Flyway for Schema Migrations

**Decision:** Replaced `SchemaUtils.createMissingTablesAndColumns` with Flyway V1–V8 migrations before Phase 1.

**Consequence:** Schema changes are versioned, reversible, and safe to run in CI/CD. No schema drift between dev and production.

### ADR-03: Audit Log Split — Write Port vs Read Repository

**Decision:** `AuditLogPort` (write-only, fire-and-forget, implemented by `PostgresAuditLogAdapter`) is separate from `AuditLogRepository` (read-only, paginated queries, implemented by `PostgresAuditLogRepository`).

**Consequence:** Auth-path audit writes never block on query logic. Admin console reads never share a code path with hot auth flows.

### ADR-04: Admin-Mutating Operations via `AdminService`, Not Direct Repositories

**Decision:** All admin write operations (create user, update workspace, regenerate secret, etc.) go through `AdminService` rather than calling repositories directly from routes.

**Consequence:** Every mutation records an audit event in the same call. Validation and business rules live in one place. Routes stay thin.

### ADR-05: Client Secret — Raw Value Never Stored

**Decision:** `regenerateClientSecret` generates a 32-byte CSPRNG secret, stores only its bcrypt hash, and returns the raw value once. The raw value is passed via a URL query parameter `?newSecret=...` and shown once in the admin UI with a "copy now" warning.

**Consequence:** Even a full database dump reveals no usable client secrets. The trade-off is that a lost secret requires regeneration (not recovery).

### ADR-06: `AdminResult<T>` Instead of Throwing Exceptions

**Decision:** `AdminService` methods return `AdminResult.Success<T>` or `AdminResult.Failure(AdminError)`. Routes pattern-match on the result.

**Consequence:** Error handling is explicit and exhaustive. No try/catch in routes. `AdminError` subtypes (`NotFound`, `Conflict`, `Validation`) map cleanly to HTTP 404/409/422.

---

## Known Deferred Items (Not Bugs — Intentional Scope)

| Item | Where it appears | Why deferred |
|---|---|---|
| `cookie.secure = true` | `Application.kt`, TODO comment | Requires TLS termination — Phase 5 |
| Portal `PortalSession` cookie → full OAuth session | `PortalSession.kt`, ADR-07 | The portal LOGIN now uses OAuth Authorization Code + PKCE (✅ done). The portal SESSION inside `/account/*` still uses an HMAC-signed `PortalSession` cookie. Replacing that with access tokens is a Phase 5 cleanup. |
| MFA pending cookie signing | `AuthRoutes.kt`, ADR-14 | Cookie `KOTAUTH_MFA_PENDING` value is plain (userId\|slug\|timestamp). Should be encrypted or use a server-side pending session — Phase 5. |
| Password expiry enforcement at login | `V13` adds `password_policy_max_age_days` column | Column exists and is saved via the security settings page, but the login path does not yet check expiry and redirect to forgot-password. |
| `required_admins` MFA policy enforcement | `MfaService.isMfaRequired` | Policy value is saved and displayed in UI. Runtime enforcement (check whether the user holds the `admin` role before requiring MFA) is not yet implemented. |
| Resource access client string keys in JWT | `JwtTokenAdapter` | `resource_access` keys currently use the integer PK as a string; should resolve to the human-readable `client_id` string. |
| WebAuthn / Passkeys | n/a | Phase 4 or 5 — `MfaMethod` enum is extensible |
| Admin UI pages for roles/groups | `AdminView.kt` has placeholder links | Phase 3c delivers JSON API routes; full HTML admin pages for role/group management are deferred |
| Admin UI page for MFA management | `AdminView.kt` | Admin-initiated MFA reset/disable for a user — no HTML page yet |
| Bulk user operations | n/a | Future |
| User impersonation | n/a | Future, requires strict audit logging |
| Admin REST API | n/a | Phase 5 |
| Prometheus metrics | n/a | Phase 5 |
| Structured JSON logs | n/a | Phase 5 |
| LDAP / SAML | n/a | Phase 4 |

---

## File Index — New Files Added

| File | Phase | Purpose |
|---|---|---|
| `domain/port/AuditLogRepository.kt` | 3a | Read-side port for audit log queries |
| `domain/service/AdminService.kt` | 3a | Admin mutation orchestration with `AdminResult<T>` return types |
| `adapter/persistence/PostgresAuditLogRepository.kt` | 3a | Implements `AuditLogRepository`; paginated audit log reads |
| `domain/model/Role.kt` | 3c | Role domain model with `RoleScope` enum (TENANT/CLIENT) |
| `domain/model/Group.kt` | 3c | Hierarchical group domain model with JSONB attributes |
| `domain/model/MfaEnrollment.kt` | 3c | MFA enrollment domain model with `MfaMethod` enum |
| `domain/model/MfaRecoveryCode.kt` | 3c | One-time recovery code domain model |
| `domain/port/RoleRepository.kt` | 3c | Port for role CRUD, composites, assignment, effective role resolution |
| `domain/port/GroupRepository.kt` | 3c | Port for group CRUD, role inheritance, hierarchy traversal |
| `domain/port/PasswordPolicyPort.kt` | 3c | Port for password validation, history, blacklist |
| `domain/port/MfaRepository.kt` | 3c | Port for MFA enrollment and recovery code persistence |
| `domain/service/RoleGroupService.kt` | 3c | Combined roles+groups domain service with cycle detection |
| `domain/service/MfaService.kt` | 3c | MFA enrollment, TOTP verification, recovery codes, policy checks |
| `adapter/persistence/RolesTable.kt` | 3c | Exposed ORM for `roles`, `composite_role_mappings`, `user_roles` |
| `adapter/persistence/GroupsTable.kt` | 3c | Exposed ORM for `groups`, `group_roles`, `user_groups` |
| `adapter/persistence/PasswordHistoryTable.kt` | 3c | Exposed ORM for `password_history`, `password_blacklist` |
| `adapter/persistence/MfaTable.kt` | 3c | Exposed ORM for `mfa_enrollments`, `mfa_recovery_codes` |
| `adapter/persistence/PostgresRoleRepository.kt` | 3c | BFS composite expansion, group ancestor traversal |
| `adapter/persistence/PostgresGroupRepository.kt` | 3c | JSONB attribute handling, hierarchy queries |
| `adapter/persistence/PostgresPasswordPolicyAdapter.kt` | 3c | Complexity, history (BCrypt), blacklist validation |
| `adapter/persistence/PostgresMfaRepository.kt` | 3c | MFA persistence with AES-256-GCM secret encryption |
| `infrastructure/TotpUtil.kt` | 3c | RFC 6238 TOTP — zero external dependencies |
| `db/migration/V12__roles_and_groups.sql` | 3c | Roles and groups schema |
| `db/migration/V13__password_policies.sql` | 3c | Password history, blacklist, tenant policy columns |
| `db/migration/V14__mfa_totp.sql` | 3c | MFA enrollments, recovery codes, tenant/user MFA columns |
| `infrastructure/PortalClientProvisioning.kt` | 3d | Startup service — ensures `kotauth-portal` client exists with correct redirect URI for every non-master tenant |
| `db/migration/V15__portal_client.sql` | 3d | Seeds one `kotauth-portal` PUBLIC client row per tenant (redirect URIs set at runtime by `PortalClientProvisioning`) |
| `db/migration/V16__default_roles.sql` | 3d | Seeds `admin` + `user` baseline roles for all existing tenants |

## File Index — Significantly Modified

| File | Phase | What changed |
|---|---|---|
| `domain/model/Application.kt` | 2 fix | Added `tokenExpiryOverride: Int?` |
| `domain/model/AuditEvent.kt` | 3a, 3c | Added 9 admin-specific variants (3a), 12 role/group variants + 6 MFA variants (3c) |
| `domain/model/User.kt` | 3c | Added `mfaEnabled: Boolean` |
| `domain/model/Tenant.kt` | 3c | Added `mfaPolicy`, `passwordPolicyHistoryCount`, `passwordPolicyMaxAgeDays`, `passwordPolicyRequireUppercase`, `passwordPolicyRequireNumber`, `passwordPolicyBlacklistEnabled` |
| `domain/model/AccessTokenClaims.kt` | 3c | Added `realmRoles`, `resourceRoles` |
| `domain/port/TenantRepository.kt` | 3a | Added `findById`, `update` |
| `domain/port/UserRepository.kt` | 3a | Added `findByTenantId` (with search), `update` |
| `domain/port/TokenPort.kt` | 3c | Added `roles: List<Role>` parameter to `issueUserTokens` |
| `domain/port/ApplicationRepository.kt` | 3a | Added `update`, `setEnabled` |
| `domain/port/SessionRepository.kt` | 3a | Added `findActiveByTenant` |
| `domain/service/AuthService.kt` | 3c | Added `PasswordPolicyPort` dependency; full policy validation in both register methods; password history recording |
| `domain/service/OAuthService.kt` | 3c | Added `RoleRepository`; resolves effective roles before token issuance in `exchangeAuthorizationCode` and `refreshTokens` |
| `adapter/token/JwtTokenAdapter.kt` | 3c | Full rewrite — embeds `realm_access` and `resource_access` claims; decodes role claims |
| `adapter/persistence/TenantsTable.kt` | 3c | Added 5 password policy columns + `mfaPolicy` |
| `adapter/persistence/UsersTable.kt` | 3c | Added `mfaEnabled` column |
| `adapter/persistence/PostgresTenantRepository.kt` | 3a, 3c | Implemented new port methods (3a); reads/writes password policy + MFA policy columns (3c) |
| `adapter/persistence/PostgresUserRepository.kt` | 3a, 3c | LIKE search + update (3a); reads/writes `mfaEnabled` (3c) |
| `adapter/persistence/PostgresApplicationRepository.kt` | 2 fix + 3a | Added `tokenExpiryOverride` mapping; implemented `update`, `setEnabled` |
| `adapter/persistence/PostgresSessionRepository.kt` | 3a | Implemented `findActiveByTenant` |
| `adapter/web/admin/AdminRoutes.kt` | 3a, 3c, 3d | Phase 3a routes + role/group CRUD (3c) + `/settings/security` GET/POST (3d); general settings POST no longer overwrites security fields |
| `adapter/web/auth/AuthRoutes.kt` | 2 fix, 3c, 3d | `call.request.origin` fix (2); MFA challenge flow (3c); all ~15 call sites updated to extract and pass `workspaceName` (3d) |
| `adapter/web/auth/AuthView.kt` | 3c, 3d | Added `OAuthParams.toQueryString()`, `mfaChallengePage` (3c); `workspaceName: String` parameter added to all 6 page functions, hardcoded "KotAuth" replaced throughout (3d) |
| `adapter/web/portal/PortalRoutes.kt` | 3c | Added MFA self-service routes (enroll, verify, disable) |
| `adapter/web/admin/AdminView.kt` | 3a, 3d | 966 → 1843 lines; 7 new page functions (3a); `workspaceSettingsPage` refactored (removed password/MFA policy sections), `securityPolicyPage` added, `workspaceDetailPage` adds "Open Portal ↗" button (3d) |
| `Application.kt` | 3a, 3c, 3d | Wired Phase 3c dependencies (3c); `PortalClientProvisioning` instantiated and called at startup (3d) |
