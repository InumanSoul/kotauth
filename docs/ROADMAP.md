# Kotauth — Product Roadmap

> Last updated: 2026-09-05
> Status: v1.24.0

---

## Strategic Context

Kotauth competes in a space occupied by Keycloak (15 years, hundreds of contributors), Auth0 (acquired by Okta for $6.5B), and Clerk ($1B+ valuation, 50+ engineers). This is not a reason to avoid the space — open source has displaced commercial IAM before — but it dictates how ruthlessly the project must phase its scope and where it must differentiate.

**Keycloak's real weaknesses (Kotauth's opportunity):**
- Admin UI is genuinely poor — dated, complex, confusing for operators who aren't OIDC experts
- Setup complexity is punishing for small teams
- Documentation assumes deep protocol knowledge upfront
- Kubernetes/cloud-native experience requires significant configuration work
- Theming requires Freemarker expertise and internal Keycloak knowledge

**Kotauth's differentiators:**
- Polished UI out of the box — the largest real gap in the open-source IAM market
- Docker/cloud-native first architecture — running in under five minutes
- Developer-first experience — discoverable, well-documented, standard protocol compliance
- Lightweight footprint — single JAR + PostgreSQL, no JBoss/WildFly dependency

**The constraint the project must hold:** Kotauth cannot compete on feature parity with Keycloak. It competes by being dramatically simpler for the 80% use case while not compromising on security fundamentals.

---

## Tenant / Application Model

The tenant and application model is an industry standard, not an opinion. Every serious IAM platform uses the same two-level structure, differing only in naming:

| Platform | Namespace unit | Application unit |
|---|---|---|
| Keycloak | Realm | Client |
| Auth0 | Tenant | Application |
| Okta | Organization | Application |
| Azure AD | Tenant | App Registration |
| Clerk | Instance | Application |
| **Kotauth** | **Workspace** | **Application** |

In protocol terms: a Kotauth Workspace is an OAuth 2.0 Authorization Server. It owns its user directory, signing keys, token policies, and identity providers. Each Application within a workspace is an OAuth 2.0 Client with its own `client_id`, allowed redirect URIs, scopes, and token lifetime overrides.

---

## Release History

### Phase 0 — Foundation ✅ Shipped
*Goal: Honest production-readiness baseline*

The initial implementation established the security and operational primitives that all subsequent phases build on:

- Email verification flow — prevents registration with unverifiable addresses
- Password reset via signed token with email delivery
- Rate limiting on `/login` (5/min) and `/register` (3/5 min) per IP
- CSRF protection on all form endpoints
- Startup validation — server refuses to start if `KAUTH_ENV=production` and unsafe defaults are detected
- Refresh token persistence with SHA-256 hashing — tokens are invalidatable, never stored in plaintext
- HTTPS enforcement at startup — OIDC discovery, OAuth callbacks, and session cookies require TLS in production
- Server-side input validation on all form and API inputs

---

### Phase 1 — Multi-Tenancy Core ✅ Shipped
*Goal: The workspace/application model that makes Kotauth a platform, not a per-app login service*

Phase 1 introduced full multi-tenancy. Every table carries `tenant_id`. The same Kotauth instance serves multiple isolated identity directories simultaneously.

- Flyway versioned schema migrations replacing `SchemaUtils` development tooling
- `tenants` table — slug-routed, each workspace owns its issuer URL, token TTLs, and password policy
- `applications` table — per-tenant OAuth2 clients with `client_id`, bcrypt-hashed secrets, redirect URIs, scope lists, and token TTL overrides
- Tenant-scoped user directory — the same email address is independent across workspaces
- Master tenant — the `master` workspace holds platform admin accounts, cleanly separated from end-user tenants
- Per-tenant RS256 key pairs — provisioned on first use, stored as PEM, no shared signing key
- Admin console — workspace list, application management, basic user management
- Tenant slug in all endpoint URLs: `/t/{slug}/protocol/openid-connect/...`

---

### Phase 2 — OAuth 2.0 / OIDC Compliance ✅ Shipped
*Goal: Standards-compliant protocol flows so any OIDC client library works with Kotauth without modification*

Full RFC 6749 / RFC 7636 / OpenID Connect Core compliance. Every OAuth2-compatible framework and client library integrates without custom adapters.

**Endpoints:**
- `GET /.well-known/openid-configuration` — OIDC discovery document
- `GET /protocol/openid-connect/certs` — JWKS for offline token verification
- `GET /protocol/openid-connect/auth` — authorization endpoint
- `POST /protocol/openid-connect/token` — all grant types
- `GET|POST /protocol/openid-connect/logout` — end session
- `GET /protocol/openid-connect/userinfo` — standard claims over bearer auth
- `POST /protocol/openid-connect/revoke` — RFC 7009 token revocation
- `POST /protocol/openid-connect/introspect` — RFC 7662 token introspection

**Grant types:**
- Authorization Code + PKCE (primary flow for web apps and SPAs — PKCE required for all public clients)
- Client Credentials (machine-to-machine)
- Refresh Token with rotation — each use issues a new refresh token; the old one is immediately invalidated

**Token claims:** `sub`, `iss`, `aud`, `exp`, `iat`, `jti`, `email`, `email_verified`, `name`, `preferred_username`, `realm_access.roles`, `resource_access.{clientId}.roles`

**Session management:** per-user session tracking, user-initiated revocation, admin-initiated revocation, absolute refresh token expiry

---

### Phase 3 — User Management Platform ✅ Shipped
*Goal: Complete admin console and user self-service — the UI/UX layer that distinguishes Kotauth from Keycloak*

Phase 3 was the largest phase, delivered in four increments (3a–3d).

**Admin console (3a):**
- User search, filter, pagination
- User detail view — profile, active sessions, audit trail
- Create, edit, and disable users
- Application management — client secret rotation, redirect URI management
- API key management — generate, scope, revoke keys with SHA-256 hashing
- Audit log viewer — paginated, filterable by event type

**Email flows and self-service (3b):**
- Email verification on registration with per-tenant SMTP configuration
- Password reset with expiring signed tokens, no email enumeration
- User self-service portal — profile editing, password change, session listing and revocation
- Per-tenant SMTP configuration with AES-256-GCM encryption of stored credentials

**RBAC and Security (3c):**
- Tenant roles and application-scoped roles
- Composite roles with BFS expansion and cycle detection at assignment time
- Groups with nested hierarchy and role inheritance — users inherit roles through group membership
- Password policies — minimum length, character class requirements, history depth, common password blacklist
- TOTP MFA (RFC 6238) — enrollment flow with QR codes, verification, recovery codes
- MFA policy per workspace: `optional`, `required`, `required_for_admins`
- REST API v1 — 30+ endpoints covering all entities, OpenAPI 3.1 spec, Swagger UI

**Branding and portal (3d):**
- Per-tenant theming — logo, primary colors, workspace name on all auth pages
- Self-service portal via OAuth Authorization Code + PKCE using the built-in `kotauth-portal` client provisioned per tenant on startup
- Dedicated Security Settings page — password policy and MFA policy configuration separated from general workspace settings

---

### Phase 4 — Webhooks ✅ Shipped
*Goal: Real-time event delivery so downstream systems stay in sync without polling*

- Webhook endpoint management — register URLs, select event subscriptions
- HMAC-SHA256 request signing — `X-KotAuth-Signature: sha256=...` on every delivery
- Asynchronous delivery — webhook fan-out does not block the auth flow
- Exponential backoff retry — three attempts: immediate, 5 minutes, 30 minutes
- Delivery history — per-endpoint attempt log with status, response code, and error detail
- Eight event types: `user.created`, `user.updated`, `user.deleted`, `login.success`, `login.failed`, `password.reset`, `mfa.enrolled`, `session.revoked`

---

### Phase 5 — Documentation and Release ✅ Shipped
*Goal: The external-facing surface that makes adoption possible*

- README with Docker quickstart — running in under five minutes from a fresh clone
- Environment variable reference — every variable, type, default, and production guidance
- React SPA + TanStack Router integration guide — end-to-end OIDC with `oidc-client-ts`, auth guards, silent token refresh
- CONTRIBUTING guide — local setup, architecture constraints, migration conventions, PR process
- Security fix: `cookie.secure` now derived from `KAUTH_BASE_URL` at startup — cookies carry the `Secure` flag automatically in HTTPS deployments

---

### Audit Log Integrity ✅ Shipped — v1.19.0
*Goal: An audit trail that can be shown to an auditor*

- Audit detail payloads built with `kotlinx.serialization` rather than hand-concatenated strings
- HMAC chain over the audit log — each row carries `prev_hash` and `row_hash`, so a deleted or edited row breaks the chain and is detectable
- Recommended Postgres role separation for `audit_log` documented for operators who want append-only enforcement at the database level

---

### Resource Indicators ✅ Shipped — v1.19.3
*Goal: A token scoped to the API it was requested for, not to everything*

- RFC 8707 `resource` parameter on the authorization-code grant, bound to the issued code and carried through to the access token's `aud` claim
- RFC 9068 §5 scope narrowing on issuance — a token targeting an API is narrowed to the intersection of the requested scopes and that API's declared scopes, across all three grant types
- Scopes editor per API in the admin console; `scopes_supported` published in the discovery document

Phase 4 of the resource-indicator work (introspection widening) remains open — see Known Gaps.

---

### Passwordless Authentication ✅ Shipped
*Goal: Sign in without a password, without weakening the account*

This is the phase previously listed below as "Phase 7 — Advanced Authentication Methods". It shipped across several releases and is recorded here rather than as future work.

- **Magic links** — short-lived signed tokens delivered by email, per-workspace TTL ([ADR-10](adr/ADR-10-magic-link-passwordless-signin.md))
- **Email OTP** — a one-time code as a passwordless primitive, with a per-workspace signup toggle and a cross-challenge lockout threshold ([ADR-15](adr/ADR-15-email-otp-passwordless-primitive.md))
- **Passkeys / WebAuthn** (v1.20.0) — platform and roaming authenticators, conditional-mediation autofill, per-credential replay defence via the WebAuthn sign counter, AAGUID device-name lookup, admin and CLI reset paths. Passkeys are a sibling to password rather than an MFA method, and a passkey with user verification satisfies an `mfa_policy=required` workspace ([ADR-16](adr/ADR-16-passkeys-sibling-to-password.md))
- **Passwordless-only workspaces** — password sign-in can be disabled per workspace, hard-gated on configured SMTP so an operator cannot strand their own users ([ADR-17](adr/ADR-17-smtp-hard-gate-passwordless-tenants.md))

**SMS OTP remains unimplemented** and is the only part of the original Phase 7 still outstanding. The multi-channel OTP port is designed but unscheduled.

---

### Operational Foundations ✅ Shipped
*Goal: The things a self-hosted operator needs before they trust it with real users*

- **Internationalization** — UI strings resolved from volume-mounted JSON bundles; English ships inside the JAR, additional locales are mounted at deploy time and never baked into the image ([ADR-11](adr/ADR-11-i18n-volume-mounted-bundles.md))
- **Redis sidecar** — distributed rate limiting and session state across replicas, fail-closed on the auth path ([ADR-12](adr/ADR-12-redis-sidecar.md))
- **Silent SSO** — path-scoped witness cookie enabling `prompt=none` re-authentication without a shared session store ([ADR-13](adr/ADR-13-oidc-sso-witness-cookie.md))
- **Admin impersonation** — an operator can act as a user with an explicit, separately-modelled session and a full audit trail ([ADR-14](adr/ADR-14-admin-impersonation-session-model.md)). This was previously listed under Phase 9
- **File-based secret injection** (`*_FILE` convention), Gradle dependency locking, and Dependabot (v1.19.1)
- **Two root compose files** and a rewritten deployment guide split into quickstart and production paths (v1.19.2)

---

### Machine-to-Machine Onboarding ✅ Shipped — v1.22.0
*Goal: Register a service as a first-class client, not as a web app with the fields left blank*

- Explicit grant types per application; the token endpoint refuses any grant the client is not registered for
- Client secret issued at creation for confidential applications, shown once
- Redirect URI conditional rather than mandatory — a client-credentials service does not need one
- Token audience settable at creation; authorized APIs reachable from the application page
- APIs moved beside Applications in the admin navigation

---

### SCIM 2.0 Provisioning ✅ Shipped
*Goal: Automated user and group provisioning from an external identity source*

- SCIM 2.0 service provider at `/t/{slug}/scim/v2` — `/Users`, `/Groups`, `/ServiceProviderConfig`, `/ResourceTypes`, `/Schemas`, implementing the RFC 7644 protocol over the RFC 7643 core schema
- API key authentication — a key carrying the `scim` scope; every request is scoped to the workspace in the path
- `externalId` correlation on users and groups — unique per workspace, so a provisioning client finds the record it created without matching on a mutable attribute
- Filtering and pagination — `eq` filters combined with `and`/`or`, `startIndex`/`count` paging, filter attributes scoped per resource type
- Deprovisioning a user deactivates rather than deletes — the account stays fetchable and its audit history is intact
- Deleting a group removes the group and its memberships — the member accounts themselves are untouched
- A group that still has subgroups is refused rather than cascaded, so a delete cannot silently destroy a subtree
- Per-key wire dialects — chosen explicitly by the operator on the API key, never sniffed from a request header; the non-default dialects normalise the wire-format deviations the major identity providers document, leaving the spec-compliant path untouched
- Admin provisioning page — endpoint URL, SCIM-scoped keys, in-place dialect correction, and a marker on every record a provisioning client owns

Conformance target is RFC 7644 and RFC 7643. No verification against, or certification for, any particular identity product is claimed.

---

### OIDC Identity Brokering ✅ Shipped
*Goal: Sign people in through an identity provider the workspace already runs*

- Generic OpenID Connect brokering per workspace — issuer URL and client credentials, with the endpoints read from the issuer's discovery document and optional per-endpoint pins for issuers that publish none
- Several brokered providers side by side in one workspace, each with its own credentials, endpoints and sign-in button label
- ID token validation in a fixed order — the header's algorithm against an allowlist before any key is fetched, then the signature, `iss`, `aud`, `azp`, `exp`, `iat`, `nonce` and `sub`
- `https` required for every issuer URL, every endpoint inside a discovery document and every JWKS URI (loopback excepted for local development); a discovery document declaring an issuer other than the one requested is refused
- Just-in-time provisioning — off by default, gated on the provider asserting a verified email and on the address's domain being on that provider's exact-match allowed list; an empty list is the feature switched off, never a wildcard
- A refused sign-in is explained to the person and recorded for the operator, with a Recent sign-in failures panel per provider carrying the reason, the email domain and a stable reference — never the address
- Test discovery on the provider form — resolves the endpoints and counts the signing keys, and says what it did not verify: the redirect URI and the client credentials

Conformance target is OpenID Connect Core and Discovery. No identity provider has been verified against a live tenant, and no verification against, or certification for, any particular identity product is claimed.

---

### Sign-In Identifier ✅ Shipped — v1.24.0
*Goal: Let people sign in with the identifier they actually know*

- Per-workspace sign-in identifier mode — `username`, `email`, or `either`. Existing workspaces default to `username` and are unaffected until an operator changes it
- Under `either`, a value matching one account's username and a different account's email is refused rather than guessed, with the same generic failure as any other outcome
- Usernames are always stored trimmed and lowercased, enforced by a database constraint rather than by application code alone; sign-in matches case-insensitively
- Cross-namespace collisions are rejected at write time across admin create, admin update, SCIM and self-registration
- A username is generated when provisioning omits one, so an integrator no longer has to invent an identifier their users have never seen
- Usernames are editable by an administrator; they were previously immutable

---

## Where the product stands

Everything in Phases 0–5 shipped, as did the whole of the original Phase 7 except SMS OTP, the impersonation item from Phase 9, and the Redis work that Phase 8 anticipated. Federation (Phase 6) is the one large phase untouched.

In protocol terms Kotauth is a compliant OAuth 2.0 / OIDC authorization server with SCIM 2.0 provisioning, OIDC identity brokering, and four sign-in methods (password, magic link, email OTP, passkey). The remaining distance to the enterprise checklist is SAML and LDAP.

---

## Known Gaps

Recorded here because a roadmap that only lists future features overstates the present.

**Scopes do not isolate clients from one another.** Authorizing a client against an API grants it every scope that API declares. There is no per-client scope allowlist, and `narrowScopes` filters against what the *resource server* declares rather than what the *client* is permitted. Measured on a live integration: a client created for a single ingest scope successfully minted a token carrying two others and read an admin endpoint across tenants. Separate credentials still buy independent revocation and a real `sub` in the audit trail — they do not buy least privilege, which is what the admin console implies they buy. Until the anticipated `allowedScopes` field exists, the only real isolation boundary is one resource server per privilege level. **This is the highest-priority open item.**

**Identity provider configuration is partially excluded from backups.** A backup export does not carry most identity-provider configuration, so a restore does not reproduce a workspace's brokered sign-in setup.

**Cross-namespace collision prevention is a read-before-write.** Two concurrent creates with mirrored identifiers can both pass the check, because no database constraint spans the username and email namespaces. The runtime refusal bounds the consequence to two users seeing a generic failure until an administrator intervenes.

**No verification against live identity products is claimed.** SCIM targets RFC 7644/7643 and brokering targets OpenID Connect Core and Discovery. Neither has been certified against, nor verified end-to-end with, any particular vendor.

---

## Post-V1 Roadmap

Priority order reflects adoption impact and dependency on existing foundations. Nothing below is scheduled.

---

### Phase 6 — Enterprise Federation
*Goal: Connect Kotauth to corporate identity sources*

The largest remaining gap, and the most common adoption blocker. Organizations with Active Directory or an existing SSO contract cannot adopt Kotauth without these.

- **SAML 2.0** — SP-initiated and IdP-initiated flows, assertion parsing, attribute mapping to the Kotauth user model
- **LDAP / Active Directory sync** — read users and groups from a corporate directory, configurable sync interval, attribute mapping
- **Claim-to-role mapping** — map inbound identity-provider claims onto Kotauth roles and groups. Smaller than the two above and useful on its own, since OIDC brokering already ships
- **Cross-workspace federation** — allow users from one workspace to authenticate in another via configured trust

---

### Phase 7 — Authorization Depth
*Goal: Make the authorization model as strong as the authentication model*

Authentication is broadly complete; authorization is where the gaps now are.

- **Per-client scope allowlists** — the `allowedScopes` field described in Known Gaps. This is the one item on this page that closes a security gap rather than adding a capability
- **OAuth consent screen** — user-facing grant approval for third-party clients
- **Resource-indicator phase 4** — introspection widening, and an ADR recording the immutable-identifier decision
- **SMS OTP** — the last unshipped part of the original passwordless phase. The multi-channel port is designed; the adapters are not. Noted as controversial due to SIM-swap risk

---

### Phase 8 — Observability and Operations
*Goal: Production-grade insight into a running deployment*

- **Prometheus / Micrometer metrics** — login rates, error rates by type, token issuance volume, active sessions, webhook delivery success
- **Key rotation** — admin-initiated rotation of per-workspace RS256 key pairs with a configurable overlap window for in-flight tokens
- **Helm chart** — Kubernetes manifests with configurable replicas, probes, and secret integration
- **Structured JSON log output** — the encoder is already in the dependency tree; field normalization remains
- **Non-blocking webhook delivery** — replace blocking `HttpURLConnection` with Ktor's `HttpClient`, paired with SSRF hardening on operator-supplied URLs
- **Audit log export** — scheduled export to object storage or a SIEM webhook

---

### Phase 9 — Platform Expansion
*Goal: Kotauth as a programmable identity layer*

- **Typed SDK** — a JVM client first, then a Kotlin Multiplatform client wrapping the REST API and OIDC flows
- **Per-workspace email templates** — HTML templates with variable substitution, sharing the existing theme tokens
- **Bulk user operations** over the admin API
- **Live session activity stream** — a `SharedFlow` over server-sent events, so the admin session view stops being static
- **IP allowlisting / geo-blocking** per workspace
- **Login and session analytics** in the admin console

---

## What Kotauth Is Not

Explicit scope boundaries prevent feature creep and keep the platform coherent:

- **Not a secrets manager** — use Vault, AWS Secrets Manager, or Doppler for application secrets
- **Not a SIEM** — audit logs are queryable and exportable; threat detection and correlation are out of scope
- **Not a CDN or WAF** — Kotauth expects a reverse proxy (nginx, Caddy, Traefik) in front of it
- **Not a directory server** — LDAP sync (read) is planned; serving the LDAP protocol is not
- **Not multi-region HA out of the box** — stateless JWT verification helps; active-active session replication is a Phase 8+ concern

---

## UI/UX Principles

Keycloak's admin console is the cautionary tale. These principles are non-negotiable:

**Progressive disclosure** — the happy path (create workspace → create application → get a working OIDC integration) takes five minutes without reading documentation. Advanced settings are always one level deeper.

**Inline guidance** — every non-obvious field gets an inline explanation or tooltip. No assumption of OIDC expertise from the operator setting up their first workspace.

**Consistent design system** — auth pages (login, register, MFA, password reset) and the admin console share the same design tokens. Theming one themes both.

**Per-tenant branding** — logo, colors, and workspace name are configurable per tenant. The login page for a given workspace reflects that workspace's brand.

**Mobile-first auth flows** — login, register, and MFA must work on mobile browsers without compromise. The admin console is desktop-first.

**Meaningful errors** — "An error occurred" is not an error message. Every failure state has a specific, actionable message that tells the user what happened and what to do about it.

---

## Architecture Decisions

Recorded ADRs live in [`docs/adr/`](adr/). This table is generated from those files — if it disagrees with them, the files win.

| ADR | Decision |
|---|---|
| [ADR-01](adr/ADR-01-hexagonal-architecture.md) | Hexagonal (Ports & Adapters) — zero framework imports in the domain |
| [ADR-02](adr/ADR-02-flyway-migrations.md) | Flyway for schema migrations |
| [ADR-03](adr/ADR-03-audit-log-split.md) | Audit log split — write port vs. read repository |
| [ADR-04](adr/ADR-04-admin-service-mutations.md) | Admin mutations go through `AdminService`, never directly through repositories |
| [ADR-05](adr/ADR-05-client-secret-hashing.md) | Client secret — the raw value is never stored |
| [ADR-06](adr/ADR-06-admin-result-sealed-class.md) | `AdminResult<T>` instead of throwing exceptions |
| [ADR-07](adr/ADR-07-permissions-server-side.md) | Fine-grained permissions are server-side only |
| [ADR-08](adr/ADR-08-multi-tenant-cors-policy.md) | Multi-tenant CORS policy |
| [ADR-09](adr/ADR-09-tenant-scoped-csp-form-action.md) | Tenant-scoped CSP `form-action` |
| [ADR-10](adr/ADR-10-magic-link-passwordless-signin.md) | Magic-link passwordless sign-in |
| [ADR-11](adr/ADR-11-i18n-volume-mounted-bundles.md) | i18n via volume-mounted JSON bundles |
| [ADR-12](adr/ADR-12-redis-sidecar.md) | Redis sidecar for distributed rate limiting and sessions |
| [ADR-13](adr/ADR-13-oidc-sso-witness-cookie.md) | OIDC silent SSO via a path-scoped witness cookie |
| [ADR-14](adr/ADR-14-admin-impersonation-session-model.md) | Admin impersonation session model |
| [ADR-15](adr/ADR-15-email-otp-passwordless-primitive.md) | Email OTP as a passwordless primitive |
| [ADR-16](adr/ADR-16-passkeys-sibling-to-password.md) | Passkeys as a sibling to password, not an MFA method |
| [ADR-17](adr/ADR-17-smtp-hard-gate-passwordless-tenants.md) | SMTP hard-gate for passwordless-only workspaces |
| [ADR-18](adr/ADR-18-group-delete-refuses-subgroups.md) | Deleting a group with subgroups is refused, not cascaded |
| [ADR-19](adr/ADR-19-merged-scim-resource.md) | `MergedScimResource` — a PATCH body reaches persistence only through the merge engine |
| [ADR-20](adr/ADR-20-scim-dialects-selected-per-key.md) | A SCIM dialect is selected per API key, never sniffed from the request |
| [ADR-21](adr/ADR-21-just-in-time-provisioning.md) | Just-in-time provisioning only creates; linking happens before the gate |
| [ADR-22](adr/ADR-22-rfc8252-loopback-redirect-matching.md) | Loopback redirect URIs match on any port, for public clients only |

### Decisions without an ADR

These are real, load-bearing choices that shape the codebase but were never written up. A previous version of this page listed them against ADR numbers that belong to other decisions. They are candidates for ADRs; until then this is the only place they are recorded.

| Decision | Rationale |
|---|---|
| Master workspace for platform admins | The platform-operator role is cleanly separated from end-user workspaces |
| Per-workspace RS256 key pairs, auto-provisioned on first use | No single point of key failure; tokens verify offline via JWKS; isolation at the cryptographic level |
| Refresh token rotation on every use | A stolen refresh token has a limited replay window; reuse of a superseded token revokes the session chain |
| PKCE required for all public clients | Defence against authorization-code interception |
| Audit events recorded eagerly in the request path | No auth flow succeeds without its audit record; background delivery risks silent loss |
| MFA pending state in an HMAC-signed cookie, not a server-side session | Stateless; no session-table bloat; the signature prevents userId forgery |
| OAuth `state` carries an HMAC-signed CSRF nonce plus the OAuth parameters | No extra cookie or session needed; the parameter is self-contained and tamper-evident |
| Portal login uses Authorization Code + PKCE via the built-in `kotauth-portal` client | The portal and third-party applications authenticate identically |
| Social login auto-links by email address | Removes friction for users holding both a local and a social account |
| Social OAuth adapters use `java.net.http` only | Lean fat JAR; no transitive dependency risk |
| Social-registered users get an unusable password hash | The account exists; password sign-in stays disabled until the user sets one |
| API keys hashed with SHA-256, not bcrypt | 256-bit key entropy makes brute force infeasible; avoids bcrypt latency on every API call |
| API key prefix stored for display | Human-friendly identification without exposing the credential |
| Swagger UI loaded from a CDN | Saves roughly 7 MB from the fat JAR |

A fifteenth entry — *user lookup by `(tenant_id, username)`, no global username namespace* — was **superseded in v1.24.0**. The workspace-scoped namespace still holds, but lookup is now by the workspace's configured identifier (username, email, or either), and usernames are normalized and uniquely enforced case-insensitively.
