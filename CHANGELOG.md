# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.5.0] - 2026-04-07

### Added

- **Invite Users** — admins can create users and send an invite email instead of setting a password. The invited user receives a branded email with a link to set their password and activate their account
- **`RequiredAction` enum** — extensible user action model stored as PostgreSQL `text[]`. Ships with `SET_PASSWORD` for invite flow. Designed as foundation for future temporary passwords (`CHANGE_PASSWORD`) and magic links
- **`TokenPurpose` enum** — discriminator on `PasswordResetToken` (`PASSWORD_RESET` / `INVITE`). Cross-purpose token usage is rejected at the service layer — invite tokens cannot be used on the reset endpoint and vice versa
- **Sentinel password hash** — `User.SENTINEL_PASSWORD_HASH = "!"` for users who haven't set a password. The `AuthService.PendingSetup` guard fires before bcrypt verification, preventing wasted CPU and providing an actionable error message
- **Accept-invite page** — `GET/POST /t/{slug}/accept-invite?token=...` branded page with password fields, client-side validation, rate limiting, and success/error states. Uses `TenantTheme` for workspace branding
- **Admin create-user form** — credential setup radio group: "Send invite email" (default when SMTP ready) / "Set password now". Uses existing `radio-row` BEM component. Invite option disabled with description when SMTP not configured
- **Invite pending badge** — amber `"Invite pending"` badge on user detail page for users with `SET_PASSWORD` in `requiredActions`
- **Resend invite** — `POST /users/{id}/resend-invite` route with toast feedback. Generates new token, invalidates old one, sends fresh email. Propagates failure with error toast when SMTP fails
- **Invite email template** — HTML + plaintext with "Set your password" CTA, 72-hour expiry notice, security footer. Subject: "You've been invited to join {Workspace Name}"
- **Audit events** — `USER_INVITE_SENT` (on create + resend) and `USER_INVITE_ACCEPTED` (on successful password set)
- **`TextArrayColumnType`** — custom Exposed column type for PostgreSQL `text[]` arrays, following the `JsonbColumnType` precedent
- **`PasswordResetTokenRepository.deleteByUserAndPurpose()`** — purpose-scoped token deletion prevents invite tokens from being invalidated by password reset flows (and vice versa)
- **`EnglishStrings`** — 16 new constants for all invite-related user-facing text
- **Migration V30** — `required_actions text[]` on users, `purpose varchar(32)` on password_reset_tokens, partial index on active tokens

### Changed

- **`AdminService.createUser`** — accepts `sendInvite: Boolean` and `password: String?` (nullable). When invite mode: stores sentinel hash, sets `requiredActions = [SET_PASSWORD]`, `emailVerified = false`, dispatches invite email. When password mode: existing behavior unchanged
- **`UserSelfServiceService.confirmPasswordReset`** — now rejects tokens with `purpose != PASSWORD_RESET` (cross-purpose guard)
- **`UserSelfServiceService` token deletion** — both existing `deleteByUser` call sites changed to `deleteByUserAndPurpose(..., PASSWORD_RESET)` to avoid silently invalidating invite tokens
- **Login page** — shows actionable message for invited users: "This account has a pending invitation. Check your email for the invite link, or ask your administrator to resend it."

### Fixed

- **Webhook E2E test** — `WebhookEventType.USER_CREATED` interpolated enum name (`USER_CREATED`) instead of `.value` property (`user.created`), causing checkbox selector timeout. Fixed to use `.value`

---

## [1.4.1] - 2026-04-07

### Added

- **Portal — connected social accounts** — new "Connected accounts" section on the self-service portal Profile page. Displays linked social providers (Google, GitHub) with provider icon and email. Empty state shown for password-only users. Uses existing `SocialAccountRepository.findByUserId()` — no migration needed
- **Entity picker component** — reusable `entityPicker()` search-as-you-type component in `AdminComponents.kt`. Replaces native `<select>` dropdowns for assigning users to roles and groups. Debounced htmx search (300ms), absolute-positioned dropdown, keyboard navigation (arrow keys, enter, escape), ARIA combobox pattern, focusout dismiss
- **RBAC assigned users table** — role detail page now shows a data table of currently assigned users (username linked to user detail, email, "Remove" button). Previously only the assignment form was visible
- **RBAC search endpoints** — `GET /roles/{id}/search-users` and `GET /groups/{id}/search-users` return HTML fragments for the entity picker, capped at 20 results. Shared `respondUserSearch()` handler eliminates code duplication
- **User list pagination** — 25 users per page with htmx-enhanced Prev/Next controls. Position-aware subtitle: "Showing 1–25 of 247 users" / "12 results for 'alice'". Search and pagination compose via `?q=alice&page=2`. Page clamped to valid range. Eliminates the old double-query anti-pattern (`listUsers` called twice)
- **Reusable `paginationControls()` component** — extracted to `AdminComponents.kt` with htmx partial-page swaps and URL push. Used by both users list and audit log
- **`UserRepository.countByTenantId()`** — dedicated count method for pagination. `SELECT COUNT(*)` instead of fetching all rows
- **`SessionRepository.countActiveByTenant()`** — dedicated count for sessions display
- **`AdminService.countUsers()`** — thin wrapper for the user count port method
- **`RoleGroupService.getUserIdsForRole()`** — delegating method for fetching assigned user IDs per role
- **Portal sidebar helpers** — extracted `workspaceInitials()` and `portalSignOutButton()` private helpers replacing duplicated code across sidenav and tabnav shell variants
- **`EnglishStrings` additions** — `PORTAL_SIGN_OUT`, `PORTAL_MY_ACCOUNT`, `PORTAL_ACCOUNT`, `CONNECTED_ACCOUNTS_TITLE`, `CONNECTED_ACCOUNTS_SUBTITLE`, `CONNECTED_ACCOUNTS_EMPTY`

### Changed

- **Sessions list capped at 100** — `findActiveByTenant` now accepts `limit`/`offset` with `Int.MAX_VALUE` defaults. The admin sessions page displays the 100 most recent active sessions with a subtitle: "Showing the 100 most recent of N active sessions" when capped
- **Audit log pagination retrofitted** — inline pagination HTML replaced with the shared `paginationControls()` component. Pagination links now include htmx attributes for partial-page swaps (previously caused full-page reloads)
- **RBAC assign/unassign toast feedback** — "User assigned to role.", "User removed from role.", "Member added to group.", "Member removed from group." toasts on all assignment actions
- **RBAC duplicate assignment prevention** — search results exclude already-assigned users via the `exclude` query param. POST handlers now check `AdminResult` and redirect gracefully on failure
- **Portal `portal-user__email` → `portal-user__handle`** — CSS class renamed to match actual content (renders username, not email)
- **JS modernized to ES2020+** — `var` → `const`/`let`, `function` → arrow functions, template literals, optional chaining across `settings.js`, `branding.js`, `confirm-dialog.js`, `auth.js`, `update-check.js`. IIFE wrappers retained for strict mode. `branding.js` `this` references replaced with closed-over parameters
- **`renderFragment()` trims whitespace** — prevents CSS `:not(:empty)` from being defeated by stray text nodes in htmx swap responses

### Fixed

- **Scope toggle JS bug** — `settings.js` compared `sel.value === 'application'` but the `<option>` emits `value = "client"`. Application-scoped role creation was silently broken — the app selector field never appeared. Fixed to match the emitted value
- **Entity picker dropdown clipped** — `.ov-card { overflow: hidden }` clipped the absolute-positioned dropdown. Added `.ov-card:has(.entity-picker) { overflow: visible }` scoped override
- **Entity picker spinner invisible** — htmx adds `.htmx-request` to the indicator element itself (not parent) when using `hx-indicator` with an explicit ID. Added self-class selector `.entity-picker__spinner.htmx-request` alongside the parent-child selector
- **Entity picker input missing `name` attribute** — htmx had nothing to serialize into the query string. Search requests never fired. Added `name="q"`

### Removed

- **`PortalView.mfaChallengePage()`** — 70 lines of dead code. MFA challenge during portal login is handled by `AuthView.mfaChallengePage()` in the OAuth auth flow
- **`AdminView.loginPage()`** — dead since OAuth PKCE migration (v1.2.0). Old password login page
- **`AdminView.workspaceRedirector()`** — dead, localStorage redirect logic no longer used
- **`loginPageImpl()`** in `AuthViews.kt` — 60-line implementation backing the removed facade
- **`workspaceRedirectorImpl()`** in `DashboardViews.kt` — 20-line implementation backing the removed facade
- **Unused `val user` binding** in `AdminService.unlockUser()` — findById call kept as not-found guard, dropped unused variable assignment
- **`allUsers` parameter** removed from `roleDetailPageImpl`, `groupDetailPageImpl`, `AdminView.roleDetailPage`, `AdminView.groupDetailPage`, and their route handlers — replaced by the entity picker search pattern. Eliminates full table scans on every role/group detail page load

---

## [1.4.0] - 2026-04-02

### Added

- **Auto-update version discovery** — background coroutine checks a remote manifest (`latest.json`) every 6 hours for new KotAuth releases. Cached result is served at `GET /health/version` with `currentVersion`, `latestVersion`, `updateAvailable`, `urgency`, `releaseUrl`, and `checkedAt` fields. The check is non-blocking, failure-tolerant (silent degradation on network errors, 404s, or malformed responses), and runs on `Dispatchers.IO` via `withContext`
- **Admin console update chip** — when an update is detected, a compact notification chip appears in the topbar-right cluster showing the available version and a link to release notes. Security updates (`urgency: "security"`) render in red with `role="alert"`. Routine updates use the accent color with `role="status"`. A pulsing dot draws initial attention (3 cycles, respects `prefers-reduced-motion`). Dismissible via localStorage — dismissal is version-scoped, so a newer release supersedes prior dismissals automatically
- **Rail version amber styling** — the version label at the bottom of the icon rail turns amber when an update is available, providing a secondary visual hint alongside the topbar chip
- **`KAUTH_UPDATE_CHECK` env var** — opt-out for air-gapped deployments. Set to `false` to disable outbound version checks entirely. Enabled by default
- **`KAUTH_UPDATE_CHECK_URL` env var** — override the manifest URL for custom mirrors or internal proxies. Defaults to `https://inumansoul.github.io/kotauth/latest.json`
- **GitHub Actions manifest workflow** — `.github/workflows/manifest.yml` triggers on `release: published`, generates `latest.json` on the `gh-pages` branch, and pushes it to GitHub Pages. Handles first-run (`gh-pages` branch creation), skips pre-releases, and derives urgency from `[security]` tag in the release body
- **Semver comparison** — internal `isNewer()` function handles `v` prefixes, pre-release suffixes, 2-part versions, and 4-part versions (truncated to 3). Pre-release of the same base version (e.g., `1.4.0-rc1` vs `1.4.0`) correctly returns `false`

### Changed

- **Quickstart compose** — `KAUTH_UPDATE_CHECK: "false"` added to `docker-compose.quickstart.yml` to avoid update banners in evaluation environments

---

## [1.3.3] - 2026-04-02

### Added

- **CSS tooltip component** — pure-CSS tooltip using `data-tooltip` attribute and `::after` pseudo-element. Uses `:has(:disabled)` to show `cursor: not-allowed` on wrappers containing disabled elements. New `tooltip-wrap` class available across the admin console
- **Disabled button styling** — `.btn:disabled` now renders at 40% opacity with `cursor: not-allowed`, consistent across all button variants
- **Toast notifications for user actions** — enable/disable user, revoke all user sessions, and resend verification email now show toast feedback ("User disabled.", "User enabled.", "All sessions revoked.", "Verification email sent.")

### Changed

- **REST API partial updates** — `PUT /users/{id}` and `PUT /applications/{id}` now accept partial payloads. All fields in `UpdateUserRequest` (`email`, `fullName`) and `UpdateApplicationRequest` (`name`, `accessType`, `redirectUris`) are optional — omitted fields retain their current values. Enables PATCH-style updates via PUT without requiring the full object
- **Audit log filter auto-submit** — the event type `<select>` now fires immediately on change via `hx-trigger="change"`, no longer requires clicking the "Filter" button
- **Settings save button feedback** — all admin settings forms (general, SMTP, identity providers, security, branding) now disable the submit button and show "Saving…" text during form submission, providing immediate visual feedback
- **Danger zone card — dynamic text** — the disable/enable user card in the user detail view now reflects the current user state: "Disable this user" / "Enable this user" with context-appropriate descriptions
- **Send Reset Email — tooltip on disabled state** — when SMTP is not configured, the disabled button is wrapped in a `tooltip-wrap` that shows "Configure SMTP to enable password reset emails" on hover
- **Webhook delivery count label** — the "Recent Delivery History" section header now shows "(last N)" indicating the visible delivery count, replacing the previous silent truncation
- **Portal session revoke confirmation** — individual session revoke buttons in the self-service portal now require confirmation via the custom dialog ("Revoke this session? The user will be signed out immediately.")

### Fixed

- **Verification email toast** — resending a verification email previously showed "Profile saved." — now correctly shows "Verification email sent."

---

## [1.3.2] - 2026-04-01

### Added

- **Workspace "Revoke all sessions"** — new button in the sessions page header with confirmation dialog. Revokes all active sessions across all users in a workspace. New `SessionRepository.revokeAllForTenant()` port method. Audit event `ADMIN_SESSIONS_REVOKED_ALL` recorded with count
- **SMTP test email button** — "Send Test Email" button on the SMTP settings page (visible when SMTP is configured). Sends a branded test email to the admin's address via `AdminService.sendTestEmail()`. New `EmailPort.sendTestEmail()` method. Audit event `ADMIN_SMTP_TEST` recorded
- **Webhook recovery sweep** — background coroutine runs every 5 minutes, queries pending deliveries via `findPending()`, and retries them. Marks deliveries as FAILED if their endpoint was deleted. Follows the session cleanup job pattern
- **Workspace logo in admin console** — workspace logos (configured in branding settings) now display in the topbar switcher badge, dropdown items, and workspace detail page. Falls back to letter initial when no logo is configured. New `workspaceAvatar()` reusable component with BEM `--sm` modifier for compact contexts
- **`WorkspaceStub` data class** — replaces `Pair<String, String>` for workspace navigation data. Carries `slug`, `name`, and `logoUrl`. Used across all admin shell calls and view functions
- **`ClientDisplayInfo` + `resolveClientLinks` helper** — resolves application IDs to both display name and OAuth `clientId` for correct URL linking in the audit log
- **Audit log event badges** — event types rendered as color-coded badges using the existing badge component: green (success), red (failure), amber (revocation/warning), blue (informational), gray (admin CRUD)
- **Audit log `<optgroup>` filter** — 55 event types grouped into 7 categories (Login & Registration, Tokens & Authorization, Sessions, Admin Actions, Email & Password, User Self-Service, MFA) for easier scanning

### Changed

- **Composite role expansion → recursive CTE** — `expandCompositeRoles()` replaced BFS loop (1 query per tree level) with a single `WITH RECURSIVE` CTE query. Uses `UNION` to prevent infinite cycles on circular role hierarchies
- **Typed webhook events** — `WebhookEvent` string constants replaced with `WebhookEventType` enum. Type-safe across model (`WebhookEndpoint.events`, `WebhookDelivery.eventType`), service (`dispatch`, `createEndpoint`), repository serialization, routes, views, and tests. Invalid event types are now impossible at compile time. No migration needed — DB column stays TEXT, string values unchanged
- **Audit log page header** — hand-rolled markup replaced with shared `pageHeader()` component
- **Audit log event types human-readable** — table rows display `login success` instead of `LOGIN_SUCCESS`, matching the filter dropdown format
- **Audit log client column linked** — client names now link to the application detail page using the OAuth `clientId` slug
- **Session revoke toast feedback** — single-session revoke shows "Session revoked." toast. Revoke-all shows "All sessions revoked." Both use distinct `?saved=` values (`revoked` / `revoked_all`)

### Fixed

- **CTE queries use `prepareStatement().executeQuery()`** — Exposed's `Transaction.exec()` internally calls `executeUpdate` which throws when a SELECT result is returned. Both CTE methods (`expandCompositeRoles`, `findAllAncestorGroupIds`) now use `connection.prepareStatement(sql, false).executeQuery()` for correct result set handling. Fixes login failure introduced by the CTE migration

---

## [1.3.1] - 2026-03-30

### Added

- **Client-side password validation** — real-time inline checklist on all password fields (register, reset-password, portal change-password, admin user creation). Shows per-tenant policy requirements as the user types: minimum length, uppercase, numbers, special characters. Appears on first keystroke, green checkmarks for met rules, red after blur. Confirm password mismatch shown on blur. `aria-live="polite"` for screen readers
- **Auto-dismissing toast notifications** — replaces persistent `?saved=true` banners across all admin settings pages and portal pages. Slides in at top-right, auto-removes after 5 seconds. Server renders `data-toast-msg` on `<body>`; JS displays and cleans the URL. Falls back to no-JS gracefully
- **`EnglishStrings` object** — centralized English strings for i18n preparation (v2.x). Password field labels, toast messages, and validation text extracted. Strings are added incrementally as views are touched
- **`FRONTEND_COMPONENTS.md`** — documents the three notification patterns (toast, alert, notice) with use cases, decision matrix, and CSS architecture (layer pattern, token sources)

### Security

- **CSP compliance** — all 9 inline `onclick`/`onchange` handlers in portal MFA pages replaced with `data-action` attributes + event delegation in `portal/mfa.js`. No more `'unsafe-inline'` violations for script execution
- **QRCode.js bundled locally** — removed CDN dependency (`cdnjs.cloudflare.com`). Library now bundled in `kotauth-portal.min.js`. Portal MFA enrollment works fully offline / air-gapped

### Changed

- **CSS token architecture** — created `base/tokens-shared.css` with structural tokens (spacing, typography, status colors) shared across all 4 bundles. Admin's `tokens.css` imports it as a superset. Auth and portal bundles import it directly. Fixes toast and password validation rendering on portal pages
- **Button CSS refactored to BEM layers** — `shared/button.css` defines the base contract (font, radius, focus-visible, color modifiers). `auth/button.css` defaults to block layout (full-width CTA). `portal/button.css` defaults to compact layout (inline actions). Portal buttons now respect tenant `border-radius` (was broken — used admin-only `--radius-sm` token)
- **Form CSS refactored to shared base** — `shared/form.css` defines common input styling (background, border, radius, focus, password toggle). Auth and portal layers add context-specific layout
- **Alert CSS follows re-export pattern** — `portal/alert.css` re-exports `shared/alert.css` (consistent with button and form pattern)
- **Portal button coherence** — primary hover uses color swap (was opacity), `font-weight` aligned to 600, `letter-spacing` added, `focus-visible` outline added, active state uses `scale(0.98)` matching auth
- **Auth button** — `font-family: inherit` → `var(--font-sans)` for explicit consistency
- **Semantic color tokens** — `--color-success` and `--color-error` aliases added to `tokens.css`, used by toast and password validation CSS
- **Register page social login icons** — Google and GitHub SVG icons now render correctly on the create account page (were empty `span` elements)
- **Portal MFA scripts extracted** — 140 lines of inline JS moved to `frontend/js/portal/mfa.js` bundle. Modern JS (const/let, async/await, descriptive names). `window._codes` replaced with module-scoped variable
- **Toast messages use `EnglishStrings`** — all 11 toast messages centralized for i18n readiness

---

## [1.3.0] - 2026-03-30

### Security

- **`KAUTH_SECRET_KEY` required in all environments** — the server refuses to start without a 32+ character key. Eliminates the previous dev-mode fallback that stored TOTP secrets in plaintext and silently discarded SMTP passwords
- **RSA private keys encrypted at rest** — tenant JWT signing keys are now AES-256-GCM encrypted in the database. Existing plaintext keys are automatically encrypted on first startup via `KeyEncryptionMigration`
- **`KAUTH_ADMIN_BYPASS` removed** — the direct-credential admin login path has been fully deleted (route handler, tests, docs). Admin authentication is exclusively via OAuth PKCE. For emergency recovery, use `java -jar kauth.jar cli reset-admin-mfa --username=admin`
- **MFA challenge rate limiting** — `POST /t/{slug}/mfa-challenge` is now limited to 5 attempts per 5 minutes per IP. Prevents brute-forcing of 6-digit TOTP codes within the MFA pending window
- **Password reset rate limiting** — `POST /t/{slug}/reset-password` is limited to 3 attempts per 5 minutes per IP. Prevents repeated password attempts against a leaked reset token
- **`findById` tenant scoping** — `UserRepository.findById(userId)` now requires `tenantId`, enforced at the database query level. Cross-tenant user lookups are structurally impossible. 33 call sites updated, redundant post-call tenant checks removed
- **Client secret removed from URL** — regenerated client secrets are no longer passed via `?newSecret=` query parameter (visible in logs, browser history, referrer headers). Uses a server-side `FlashStore` with one-time read semantics
- **FK indexes** — V29 migration adds 12 missing foreign key indexes across `sessions`, `authorization_codes`, `audit_log`, `composite_role_mappings`, `group_roles`, and 5 tenant-scoped tables. Prevents sequential scans on `DELETE CASCADE` operations

### Added

- **CLI infrastructure** — the JAR now supports subcommands: `java -jar kauth.jar cli <command>`. Dispatched in `Application.kt`, no CLI framework dependency
- **`generate-secret-key` command** — generates a cryptographically secure 32-byte hex key for `KAUTH_SECRET_KEY`. Pure crypto, no database connection required
- **`reset-admin-mfa` command** — resets MFA enrollment for a locked-out admin on the master tenant. Connects to the database without running Flyway migrations
- **HTTP response compression** — gzip (priority 1.0) and deflate (priority 0.9) with 1024-byte minimum. Images excluded
- **Static asset cache headers** — CSS and JS get `Cache-Control: public, max-age=31536000` with `?v=` version query param for cache busting. HTML gets `Cache-Control: no-cache`
- **Global htmx loading indicator** — thin accent-colored progress bar at the top of every admin page during htmx requests
- **Rate limiting documentation** — `docs/RATE_LIMITING.md` covering all protected endpoints, architecture, memory management, and planned additions
- **`AdminDisplayHelpers`** — shared display utility for resolving user IDs and client IDs to human-readable names across admin views
- **Makefile targets** — `make generate-key` and `make reset-mfa USER=admin` convenience wrappers

### Changed

- **`EncryptionService` is always available** — constructor takes non-nullable `String`. The `isAvailable` property and all branching on it have been removed from `EncryptionPort`, `PostgresMfaRepository`, `PostgresTenantRepository`, `AdminSettingsRoutes`, `ServiceGraph`, `Application.kt`, and health/welcome routes
- **`AdminService` expanded** — new methods: `getUser`, `listUsers`, `toggleUserEnabled`, `createWorkspace`. Route handlers no longer call `UserRepository` directly
- **ADR-04 compliance** — `AdminUserRoutes`, `AdminSessionAuditRoutes`, and `ApiUserRoutes` no longer receive `UserRepository` as a parameter. All user operations go through `AdminService`
- **Workspace creation validation** — slug format, reserved names, uniqueness, and display name checks moved from the route handler into `AdminService.createWorkspace()` with sealed `AdminResult` return
- **`resolveEffectiveRoles` performance** — replaced N+1 ancestor group traversal with a single recursive CTE query. For 3 groups × 5 levels deep, reduces from ~15 DB roundtrips to 1
- **Rate limiter hardened** — `InMemoryRateLimiter` now tracks `lastAccess` for LRU eviction, configurable `maxKeys` cap (default 10,000), two-phase eviction (prune expired, then LRU). Dead `hitCount` field removed
- **IdP form toggle** — enabling a new identity provider now respects the toggle value on first save (was hardcoded to `false`)
- **Workspace creation form** — removed branding card (accent color + logo URL were silently discarded). Added `(optional)` to Issuer URL label. Registration policy checkboxes now use `check-row__body` pattern with descriptions
- **Audit log** — breadcrumb uses standard component with `›` separators. Filter bar inside htmx swap target so Clear button updates correctly. `activeAppSection` fixed from `"events"` to `"audit"`
- **Error alerts** — 3 legacy `alert alert-error` instances in `UserViews` replaced with `notice notice--error`
- **RBAC tables** — `key-table` class replaced with `data-table` across all RBAC detail views
- **MFA tables** — bespoke `mfa-user-*` classes replaced with `data-table__*` for consistency
- **Rail navigation** — workspace-dependent nav items render as disabled ghosts when no workspace is selected
- **Topbar search** — wired to navigate to user list with `?q=` when a workspace is selected. Disabled with tooltip when no workspace is selected
- **Application detail** — client secret display uses `copy-field` pattern with copy button (was plain text in a notice)
- **Session revoke** — per-row confirmation dialog added on user detail page
- **Webhook deliveries** — removed redundant `.take(50)` in view (already limited at query level)
- **Workspace detail** — removed duplicate "New Application" CTA from section label
- **Create application** — sidebar hidden (was rendering empty)
- **Roles/Groups headers** — `span` → `p` for subtitle element

### Removed

- **`KAUTH_ADMIN_BYPASS`** environment variable and all supporting code
- **`EncryptionPort.isAvailable`** property — encryption is always available
- **`isAvailable` branching** in `PostgresMfaRepository`, `PostgresTenantRepository`, `AdminSettingsRoutes`, `ServiceGraph` session key derivation
- **Direct `UserRepository` access** from `AdminUserRoutes`, `AdminSessionAuditRoutes`, `ApiUserRoutes`, `ApiRoutes`

---

## [1.2.1] - 2026-03-27

### Added

- **`/authorize` endpoint** — industry-standard OAuth authorization URL. `GET /authorize` validates params and sets a server-side auth context cookie. `POST /authorize` processes credentials with the full security pipeline (lockout, MFA, rate limiting, password expiry). Replaces the old `/protocol/openid-connect/auth` (backward compat redirect preserved) and eliminates all hidden OAuth form fields
- **Server-side auth context cookie** (`KOTAUTH_AUTH_CONTEXT`) — signed cookie scoped to `/t/{slug}` replaces hidden form fields for carrying OAuth state through the login flow. Fixes double-login in incognito mode and survives page refreshes
- **JS bundling with esbuild** — source files in `frontend/js/`, compiled into 4 minified bundles: `kotauth-admin.min.js` (53KB), `kotauth-auth.min.js` (1.6KB), `kotauth-portal.min.js` (1KB), `branding.min.js` (3.3KB). SRI integrity hashes generated at build time via `js-integrity.properties`
- **Password show/hide toggle** — eye icon on all 5 password fields across login, register, and reset-password forms. Server-rendered SVGs with CSS-based icon swap. New `auth.js` for auth page interactions
- **Custom confirmation dialog** — `<dialog>` element replaces browser `confirm()` across admin console and portal. Themed via CSS custom properties, backdrop fade + card fade-in-up animation. No `window.confirm()` fallback
- **Portal brand logo** — tenant-configured `logoUrl` displayed in portal topbar and sidebar, replacing initials when available
- **Session revocation DB check** — portal and admin session guards now validate the backing DB session on every request. Revoking a session from the admin console immediately invalidates the user's cookie
- **Swagger UI bundled locally** — CSS/JS assets served from `/static/swagger/`, no CDN dependency. Works in air-gapped environments. Branded dark topbar with accent authorize button

### Security

- **`POST /t/{slug}/login` removed** — no standalone credential endpoint exists. All authentication goes through `POST /authorize` which enforces the full security pipeline. The only direct-auth path is `POST /admin/login`, gated by `KAUTH_ADMIN_BYPASS`
- **Rate limiting on `POST /authorize`** — login rate limiter enforced on the new authorize endpoint
- **SRI integrity hashes** on all JS bundle `<script>` tags — prevents tampering with static assets
- **CSP updated** — allows Google Fonts (`style-src`, `font-src`) and HTTPS tenant logos (`img-src https:`)

### Changed

- **OIDC discovery `authorization_endpoint`** now advertises `/t/{slug}/authorize`
- **Portal + admin PKCE redirects** point to `/authorize`
- **All "Sign in" links** in auth views and email templates point to `/t/{slug}/account/login` (portal login which starts a proper OAuth flow)
- **Post-registration redirect** — OAuth-aware: if auth context cookie exists, returns to `/authorize?registered=true`; standalone → `/account/login`
- **Email templates** — shared `buildEmailHtml()` layout with TenantTheme branding (accent button, logo, font, border radius). Responsive table-based layout with `max-width:480px` fluid fallback
- **Sessions/audit tables** — user IDs resolved to clickable usernames, client IDs resolved to application names
- **Audit log page size** reduced from 50 to 20 per page
- **Confirmation dialogs** added to disable-user and revoke-all-sessions buttons
- **Error in URL** fixed — send-reset-email failure uses `?saved=` flag instead of URL-encoded error message
- **htmx: user search** — debounced `hx-get` with `hx-replace-url`, "N of M users" subtitle
- **htmx: audit filter** — in-place table update with `hx-push-url`, pagination carries htmx attributes
- **All `<script>` tags** now use `defer` for non-blocking page rendering
- **Dockerfile** — stage 1 renamed `frontend-build`, includes JS compilation + SRI generation

### Removed

- **`LoginRoutes.kt`** — deleted entirely. No `/t/{slug}/login` route exists
- **Hidden OAuth form fields** — ~70 lines of `<input type="hidden">` elements removed from login and MFA pages
- **Individual JS source files** from `src/main/resources/static/js/` — replaced by compiled bundles

---

## [1.2.0] - 2026-03-27

### Added

- **Admin Console OAuth Dogfooding** — the admin console now authenticates via OAuth Authorization Code + PKCE through the master tenant, replacing direct password auth. Admin login flows through the same auth pipeline as every other Kotauth consumer, gaining MFA enforcement, session tracking, and token revocation for free
- **Admin role gating** — a `admin` role is provisioned on the master tenant (V28 migration). Only users with this role can access the admin console. Enforced in both OAuth and bypass modes
- **OIDC end-session logout** — admin logout revokes the DB session, clears the cookie, and redirects through the OIDC end-session endpoint with `id_token_hint` for proper RP-initiated logout
- **Break-glass bypass** — `KAUTH_ADMIN_BYPASS=true` environment variable keeps the old direct password login available for recovery scenarios. Defaults to `false`. Startup warning logged when active
- **Account lockout** — per-user failed login attempt counter with configurable threshold (default: 10) and lockout duration (default: 15 min). Disabled by default — admin opt-in per tenant. Admin can unlock users from the admin console. Users receive an email notification with a password reset link when locked
- **`SecurityConfig` extraction** — password policy, MFA policy, and lockout config moved from `tenants` table to dedicated `tenant_security_config` table (V26), following the existing `TenantTheme` and `PortalConfig` pattern
- **Account locked email** — async notification with lockout duration and password reset CTA. Gated by `tenant.isSmtpReady`
- **Password changed email** — async security notification sent on all password change paths (self-service, reset link, admin-initiated). No CTA link to prevent phishing surface
- **`KAUTH_ADMIN_BYPASS`** environment variable — controls whether direct credential login is available on the admin console
- **Admin client auto-provisioning** — `AdminClientProvisioning` ensures the master tenant has a `kotauth-admin` public OAuth client with the correct redirect URI, issuer URL, and branding logo at startup

### Security

- **HMAC-signed admin cookie** — `KOTAUTH_ADMIN` session cookie now uses `SessionTransportTransformerMessageAuthentication` with a dedicated `adminSessionKey` (different derivation prefix from portal)
- **OAuth `state` parameter** — CSRF protection on both admin and portal OAuth flows. Random state embedded in signed PKCE cookie and verified on callback
- **Open redirect prevention** — OIDC end-session endpoint now validates `post_logout_redirect_uri` against the request origin. External URIs are rejected
- **PKCE cookie `Secure` flag** — both admin and portal PKCE cookies now set `secure` based on `baseUrl` scheme
- **Portal security parity** — portal OAuth flow upgraded with `state` CSRF parameter, `secure` cookie flag, and `kotlinx.serialization` JWT parser (replacing fragile regex)
- **Master tenant registration disabled** — V28 sets `registration_enabled = false` on master tenant. Login page hides "Create an account" when registration is off

### Changed

- **Shared OAuth utilities** — `generatePkceVerifier()`, `generatePkceChallenge()`, and `decodeJwtPayload()` extracted from duplicated private functions in AdminRoutes and PortalRoutes to shared `OAuthUtils.kt`. JWT parser upgraded from regex to `kotlinx.serialization`
- **Admin session model** — expanded from `AdminSession(username)` to include `userId`, `tenantId`, `accessToken`, `idToken`, `adminSessionId`. Sessions are backed by real entries in the sessions table
- **Admin session TTL** — reduced from 8 hours to 1 hour to match access token expiry
- **Master tenant defaults** — startup provisioning sets issuer URL from `KAUTH_BASE_URL`, logo from built-in brand asset, sharp border radius. Replaces the V1 placeholder `kauth.example.com`
- **Login page** — hides "Don't have an account? Create one" when `registrationEnabled = false`
- **Brand logo sizing** — `width="180" height="48"` on auth page logos for correct rendering without CSS dependency

### Fixed

- **Locked badge visibility** — user list shows amber "Locked" badge (distinct from gray "Disabled"). Precedence: Disabled > Locked > Active
- **SecurityConfig upsert** — `PostgresTenantRepository.update()` now uses upsert pattern for `tenant_security_config`, fixing settings not being saved for tenants created after V26
- **V28 role scope** — uses lowercase `'tenant'` matching the DB check constraint

### Removed

- **Legacy `kotauth-admin-console`** confidential client — replaced by `kotauth-admin` public PKCE client in V28
- **Legacy tenant policy columns** — V27 drops `password_policy_*` and `mfa_policy` from `tenants` table (data migrated to `tenant_security_config` in V26)

---

## [1.1.5] - 2026-03-26

### Fixed

- **Broken verification email links** — `AuthService.register()` passed an empty `baseUrl` to email verification, producing relative URLs that don't work in email clients. Deleted the duplicate 6-param overload, wired `baseUrl` through the route layer
- **Audit log details always empty** — `PostgresAuditLogRepository.toAuditEvent()` now parses the JSONB `details` column via `kotlinx.serialization.json.Json`. API consumers and admin UI now see actual audit event details (IP changes, session IDs, etc.)
- **OAuth context lost on password expired redirect** — password expired redirect during an OAuth flow now preserves all OAuth params in the query string
- **CSP violation on admin redirect** — replaced inline JS workspace redirector with server-side cookie (`kotauth_last_ws`) + direct redirect. Replaced inline `onchange` handler with `data-autosubmit` attribute
- **Rate limit keys now tenant-scoped** — changed from `login:$ip` to `login:$ip:$slug` across all 4 rate-limited endpoints. One tenant's traffic no longer affects another's budget
- **Rate limiter memory leak** — `InMemoryRateLimiter` now prunes idle buckets when the map exceeds 1,000 keys
- **`toRole()` N+1 query** — removed per-row composite child query from the role mapper. `RoleGroupService.listRoles()` now batch-fetches all child mappings in one query via `findAllChildMappings()`

### Added

- **Composite database indexes** (V25 migration) — `idx_sessions_tenant_user_active` for session lookups and `idx_audit_tenant_created` for audit log queries. Covers the most frequent query patterns
- **Shared `applicationScope`** — coroutine scope in `ServiceGraph` shared by `WebhookService` and `UserSelfServiceService`. Cancelled on shutdown to allow in-flight work to complete
- **Session cleanup job** — background coroutine runs hourly, purging expired and revoked sessions older than 7 days
- **`sha256Hex` shared utility** — extracted from 5 duplicate private functions into `domain/util/Hashing.kt`

---

## [1.1.4] - 2026-03-26

### Security

- **CVE-2025-55163** — Netty HTTP/2 DDoS vulnerability. Mitigated by constraining `netty-codec-http2` to 4.1.124.Final
- **CVE-2025-24970** — Netty native SSL crash on crafted packet. Mitigated by constraining `netty-handler` to 4.1.124.Final
- **GHSA-72hv-8253-57qq** — Jackson async parser DoS. Mitigated by constraining `jackson-core` to 2.18.6
- **CVE-2025-11226 / CVE-2026-1225** — Logback arbitrary code execution. Fixed by upgrading to 1.5.32
- **CVE-2025-49146** — PostgreSQL JDBC MITM attack. Fixed by upgrading to 42.7.10
- **CSRF protection** — Added `SameSite=Lax` attribute to both `KOTAUTH_ADMIN` and `KOTAUTH_PORTAL` session cookies
- **Content Security Policy** — Added `Content-Security-Policy` header to all responses (`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action 'self'`)
- **Thread-safe JWT cache** — Replaced `mutableMapOf` with `ConcurrentHashMap` in `JwtTokenAdapter.algorithmCache` to prevent data race under concurrent token issuance

### Fixed

- **Webhook `X-KotAuth-Event` header** — was incorrectly sending the endpoint URL instead of the event type (e.g., `user.created`). Receivers relying on this header for event routing now get the correct value

### Changed

- **Dependency upgrades** (no breaking changes):
  - Logback 1.4.14 → 1.5.32
  - PostgreSQL JDBC 42.7.3 → 42.7.10
  - Logstash encoder 7.4 → 8.0
  - Exposed 0.50.1 → 0.55.0
  - java-jwt 4.4.0 → 4.5.1
  - MockK 1.13.10 → 1.13.16
  - JUnit Jupiter 5.10.2 → 5.10.5

### Removed

- **`ktor-server-auth-jwt`** dependency — declared but unused (zero imports). All JWT operations use `com.auth0:java-jwt` directly

---

## [1.1.3] - 2026-03-25

### Added

- **HikariCP connection pool** — replaced bare JDBC `DriverManager.getConnection()` (new TCP connection per transaction) with HikariCP 5.1.0 pooled connections. Eliminates 10-20ms of TCP/TLS/auth overhead per DB call. Pool configured with leak detection (4s threshold), connection keepalive, and max lifetime rotation
- **`DB_POOL_MAX_SIZE`** environment variable — configurable maximum pool size (default: 10)
- **`DB_POOL_MIN_IDLE`** environment variable — configurable minimum idle connections (default: 2)
- **Multi-arch Docker images** — publish workflow now builds `linux/amd64` and `linux/arm64` natively in parallel using GitHub's free arm64 runners. No QEMU emulation

### Changed

- **Async email delivery** — verification and password-reset emails are now sent in a background coroutine (`CoroutineScope + SupervisorJob + Dispatchers.IO`), matching the existing async webhook pattern. HTTP responses return immediately instead of blocking on SMTP
- **Admin route intercepts** — extracted ~60 duplicate `findBySlug` + `findAll` calls from 7 admin sub-route files into a single `intercept(ApplicationCallPipeline.Call)` block at the `/{slug}` route level. Workspace and sidebar data resolved once per request via `call.attributes`
- **Auth route intercepts** — extracted ~21 duplicate `findBySlug` calls from 6 auth sub-route files into a single intercept at the `/t/{slug}` route level. Tenant, theme, and workspace name resolved once per request via `AuthTenantContext`

---

## [1.1.2] - 2026-03-25

### Added

- **External database compose file** — `docker/docker-compose.external-db.yml` runs only the Kotauth container, no bundled PostgreSQL. For managed providers (RDS, Supabase, Neon) or any existing PostgreSQL instance
- **"Bring your own database" section** in README
- **`CODE_OF_CONDUCT.md`** — Contributor Covenant for community guidelines

### Changed

- **CONTRIBUTING.md** — replaced `./gradlew` commands with `make` targets throughout; added full Makefile target reference table, typical dev loop, and IDE setup instructions
- **kotauth-docs external database guide** — replaced workaround (override file + `docker compose up app`) with dedicated `docker-compose.external-db.yml`
- **kotauth-docs Docker page** — added external database compose file to the compose file listing, updated production examples to show both bundled and external DB paths
- **`.env.example`** — added reference to `docker-compose.external-db.yml` in the external database section

---

## [1.1.1] - 2026-03-24

### Added

- **Zero-config quickstart** — `docker-compose.quickstart.yml` at repo root for one-command local evaluation with demo data pre-loaded
- **Live demo and docs links** in README header

### Changed

- **README restructured** — "Try it — one command" section leads the quickstart; existing configurable setup moved to second section
- **DB_URL marked optional** in environment variable table — auto-constructed from `DB_HOST`/`DB_PORT`/`DB_NAME` when not set
- **PostgreSQL port no longer exposed** to host in `docker/docker-compose.yml` — only accessible within the Docker network

### Fixed

- Broken link to `docs/IMPLEMENTATION_STATUS.md` in README — now points to `docs/adr/`
- Missing `-o` flags in kotauth-docs quickstart curl commands — files were printed to stdout instead of saved
- Stale Docker image tag examples (`1.0.1` → `1.1.1`) in documentation site
- Incorrect CSS bundle count in docs ("two" → "four": admin, auth, portal-sidenav, portal-tabnav)

---

## [1.1.0] - 2026-03-22

### Changed

- **Split AdminRoutes.kt** (~1831 lines) into 7 focused route files under `adapter/web/admin/`: `AdminSettingsRoutes`, `AdminApplicationRoutes`, `AdminUserRoutes`, `AdminSessionAuditRoutes`, `AdminRbacRoutes`, `AdminApiKeyRoutes`, `AdminWebhookRoutes`. The orchestrator is now ~279 lines
- **Split AuthRoutes.kt** (~1764 lines) into 7 focused route files under `adapter/web/auth/`: `LoginRoutes`, `RegisterRoutes`, `SelfServiceRoutes`, `MfaRoutes`, `SocialLoginRoutes`, `OAuthProtocolRoutes`, `AuthHelpers`. The orchestrator is now ~80 lines
- **Extracted ServiceGraph** — composition root moved from `Application.kt` into `config/ServiceGraph.kt` with `EnvironmentConfig` for fail-fast env validation
- **EncryptionService** converted from `object` singleton to injectable `class` — receives secret key via constructor, no more `System.getenv()` calls
- **RateLimiter** extracted behind `RateLimiterPort` interface in domain layer — routes depend on port, not concrete implementation
- **AuthService.login()** now delegates to `authenticate()` internally — eliminates duplicated validation logic

### Removed

- Legacy admin redirect routes (`/admin/settings`, `/admin/users`, etc.) — V1 has no backward-compat burden

---

## [1.0.3] - 2026-03-19

### Added

- **BEM design system — settings pages**: Rewrote Security Policy, SMTP, Identity Providers, API Keys, and Webhooks pages from legacy CSS to BEM components (`ov-card`, `edit-row`, `toggle-row`, `chip-grid`, `copy-field`, `key-table`)
- **BEM design system — RBAC pages**: Rewrote Roles (list/create/detail) and Groups (list/create/detail) to BEM with `data-table`, `page-header`, `edit-row`, `key-table`, and `empty-state` components
- **`settings.js`** — CSP-safe JavaScript for all admin interactions: clipboard copy (`data-copy`), confirm dialogs (`data-confirm`), chip-grid toggles (`data-chips-all`/`data-chips-none`), scope field toggle (`data-scope-toggle`)
- **`copy.svg`** icon for clipboard copy buttons
- **`btn--icon`** CSS modifier for icon-only square buttons (24×24)
- **`ov-card__section-label--danger`** CSS modifier for danger zone headings
- **`badge--danger`** CSS modifier (red variant) for failed delivery status
- **`copy-field`** CSS component for monospace value + copy button (callback URLs, one-time secrets)
- **Frontend Architecture docs** — added JavaScript (`settings.js`) and htmx patterns sections to `docs/FRONTEND_CSS.md`

### Changed

- **`copyBtn()` rewritten** — replaced inline `onclick` with `data-copy` attribute and SVG icon; now uses `btn btn--ghost btn--icon` base classes
- **Lock icon** — replaced 🔒 emoji with inline SVG (`lock.svg`) on user detail page
- **Copy buttons across all pages** — replaced `⎘` unicode glyph with `copy.svg` icon in Identity Providers, API Keys, and Webhooks copy-field buttons
- **User detail page** — converted Profile, Active Sessions, and Danger Zone from legacy `section`/`section__title` to `ov-card`/`ov-card__section-label`
- **Branding page** — replaced `form-section__label` with `ov-card__section-label`; each section now wrapped in its own `ov-card`
- **ov-card stacking** — increased sibling gap from 12px to 20px
- **`ov-card__section-label`** — now flex row (`justify-content: space-between`) to support action buttons alongside the title
- **`empty-state__icon`** — added `color: var(--color-muted)` and `svg { width: 16px; height: 16px }` for consistent icon rendering
- **Reset password button** — now always visible on user detail page; disabled with tooltip when SMTP is not configured
- **`docs/FRONTEND_CSS.md`** — updated folder structure, file responsibilities table, and component reference to reflect all new BEM components and CSS files

### Fixed

- **Global label bleed** — added explicit `text-transform: none` resets on `.check-row`, `.radio-row`, `.toggle`, and `.scope-chip` to counteract global `label { text-transform: uppercase }` from `form.css`
- **Checkbox/radio sizing** — added `padding: 0; min-width: 14px; min-height: 14px` resets to prevent global `input` padding from inflating checkbox and radio inputs
- **Branding double-spacing** — removed flex gap from `.branding-form` to prevent stacking with ov-card sibling margin
- **Branding double-border** — removed `border` from `.preset-group` and `.color-grid` now that they sit inside bordered ov-cards

---

## [1.0.2] - 2026-03-19

### Added

- `docker/docker-compose.prod.yml` — production overlay that adds a Caddy sidecar for automatic Let's Encrypt TLS
- `docker/Caddyfile` — Caddy reverse proxy configuration used by the production compose stack
- `docker/docker-compose.dev.yml` — dedicated compose file for contributors building from source (`make up`)
- `DB_HOST` environment variable — hostname component for constructing the JDBC URL (default: `db`)
- `DB_PORT` environment variable — port component for constructing the JDBC URL (default: `5432`)
- `.env.example` committed to the repository as the canonical environment template
- `docs/guides/production-deployment.md` — full production deployment walkthrough covering Caddy, nginx, Traefik, upgrades, and backup

### Changed

- Docker files reorganised into a `docker/` subdirectory (`docker-compose.yml`, `docker-compose.dev.yml`, `docker-compose.prod.yml`, `Caddyfile`)
- `docker/docker-compose.yml` now uses the pre-built GHCR image (`ghcr.io/inumansoul/kotauth:latest`) instead of building from source — no repo clone required to run Kotauth
- `DB_URL` is now an optional override. When not set, the compose stack constructs it from `DB_HOST`, `DB_PORT`, and `DB_NAME`. Setting `DB_URL` directly takes full precedence, enabling external or managed database connections (RDS, Supabase, Neon, etc.) with custom JDBC parameters such as `?sslmode=require`
- `README.md` rewritten: zero-clone quickstart as the primary path, CI/Docker/release/license badges, available image tags table
- Makefile `make up` and related targets updated to reference `docker/docker-compose.dev.yml`

### Fixed

- `DB_URL` in the compose stack was hardcoded and would silently override any `DB_URL` value set in `.env`
- GitHub repository URL placeholder (`your-org/kotauth`) corrected throughout docs and config

---

## [1.0.1] - 2026-03-19

### Added

- Admin console welcome page with live health details in development mode
- New helper utilities for internal route handling

---

## [1.0.0] - 2026-03-17

Initial stable release.

### Added

- **OAuth2 / OIDC provider** — Authorization Code + PKCE, Client Credentials, Refresh Token rotation, token introspection (RFC 7662), token revocation (RFC 7009), OIDC discovery (RFC 8414), JWKS endpoint
- **Multi-tenancy** — fully isolated workspaces with per-workspace RS256 key pairs, user directories, OAuth clients, token policies, and branding; master workspace for platform administration
- **RBAC** — tenant-scoped and client-scoped roles, composite role inheritance with BFS expansion and cycle detection, group hierarchy with nested membership
- **MFA** — TOTP (RFC 6238) with QR code enrollment, 10 recovery codes per user, per-workspace policy (`optional`, `required`, `required_for_admins`)
- **Social login** — Google and GitHub OAuth2 with automatic account linking by email address
- **User self-service portal** — email verification, password reset (no email enumeration), profile editing, password change, session listing and revocation, MFA enrollment
- **Admin console** — workspace settings, user management, application management, API key management, audit log viewer, webhook management
- **Webhooks** — HMAC-SHA256 signed delivery for 8 event types with exponential backoff retry (immediate, 5 min, 30 min)
- **REST API v1** — 30+ endpoints with API key authentication; OpenAPI 3.1 spec and Swagger UI at `/t/{slug}/api/v1/docs`
- **Audit logging** — 30+ immutable event types, append-only, queryable via API and admin console
- **Security baseline** — bcrypt passwords, AES-256-GCM SMTP credential encryption, SHA-256 refresh token and API key hashing, IP-based rate limiting, security response headers, CSRF protection, startup validation for production mode
- **Flyway migrations** — versioned schema management, runs automatically on startup
- **Multi-stage Docker build** — ~120 MB runtime image (`eclipse-temurin:17-jre`), published to `ghcr.io/inumansoul/kotauth`
- **CI/CD** — GitHub Actions pipelines for lint, test, and Docker image publishing on version tags
- **Integration guide** — React SPA with TanStack Router and `oidc-client-ts`

---

[Unreleased]: https://github.com/inumansoul/kotauth/compare/v1.2.1...HEAD
[1.2.1]: https://github.com/inumansoul/kotauth/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/inumansoul/kotauth/compare/v1.1.5...v1.2.0
[1.1.5]: https://github.com/inumansoul/kotauth/compare/v1.1.4...v1.1.5
[1.1.4]: https://github.com/inumansoul/kotauth/compare/v1.1.3...v1.1.4
[1.1.3]: https://github.com/inumansoul/kotauth/compare/v1.1.2...v1.1.3
[1.1.2]: https://github.com/inumansoul/kotauth/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/inumansoul/kotauth/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/inumansoul/kotauth/compare/v1.0.3...v1.1.0
[1.0.3]: https://github.com/inumansoul/kotauth/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/inumansoul/kotauth/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/inumansoul/kotauth/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/inumansoul/kotauth/releases/tag/v1.0.0
