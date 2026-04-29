# ADR-11: i18n via volume-mounted JSON bundles

**Status:** Accepted (v1.7.2)
**Date:** 2026-04-28
**Supersedes:** —
**Related:** ADR-01 (hexagonal architecture), ADR-02 (Flyway migrations)

## Context

Two simultaneous needs forced i18n forward from its v2.1 slot:

1. The Oriana platform integration ships against a Spanish-speaking user
   base; English-only auth pages would surface as a regression at first
   sign-in.
2. An enterprise deal in flight lists Spanish UI as a contractual
   requirement.

Neither party wants to wait for v2.1. The constraint that shapes
everything below: **English remains the always-on default for the JAR
distribution.** A vanilla `docker run` install must render English with
zero configuration, and an air-gapped install must work without ever
producing a translation file. Spanish — and any other locale — is an
extra feature operators opt into.

Within that constraint we needed to decide:

- Where translations live (sidecar process, embedded resource, mounted
  file, remote service).
- How the application discovers and loads them.
- How locale gets resolved per request and per tenant.
- How the existing `*View.kt` view layer evolves without an explosion of
  signature changes.

## Decision

### Architecture: a `TranslationPort` with two adapters

```
domain/port/TranslationPort.kt    (pure interface)
infrastructure/EnglishOnlyTranslation.kt   (default — baked in)
infrastructure/BundleTranslation.kt        (opt-in — volume mount)
```

`TranslationPort` is part of the domain because views are part of the
delivery boundary and need a domain-shaped contract; the implementations
live in `infrastructure/` next to other framework-aware adapters.
The interface is tiny:

```kotlin
interface TranslationPort {
    val availableLocales: Set<String>          // always contains "en"
    fun t(key: String, locale: String, vararg args: Any?): String
}
```

`EnglishOnlyTranslation` is wired by `ServiceGraph` when
`KAUTH_I18N_BUNDLE_DIR` is unset. It pulls strings from `EnglishStrings`
via reflection (`byKey: Map<String, String>` derived from declared
`const val String` fields). `BundleTranslation` is wired when the env
var points at a directory; it loads each `<locale>.json` once at
startup, falls back to `EnglishOnlyTranslation` on every cache miss, and
adds `"en"` to `availableLocales` unconditionally.

### Bundles are flat key→string JSON, mounted at runtime

```jsonc
// /etc/kotauth/i18n/es.json
{
  "PASSWORD": "Contraseña",
  "AUTH_PAGE_TITLE_LOGIN": "{0} | Iniciar sesión"
}
```

- Keys are the `EnglishStrings` field name (e.g. `PASSWORD`,
  `LOGIN_SUBMIT`, `AUTH_PAGE_TITLE_LOGIN`).
- Values may include positional placeholders `{0}`, `{1}`, … which are
  substituted at render time.
- `en.json` in the bundle dir is **ignored with a warn log** — the JAR's
  English source of truth is authoritative and cannot be hot-patched.
- Malformed JSON or non-string values cause that one file to be skipped
  with a warn log; other bundles still load.

A `KAUTH_I18N_BUNDLE_DIR` env var picks the directory. There is no
classpath scanning — operators control which locales exist by which
files they mount.

### Locale resolution priority

Per request, in `LocaleResolution.kt`:

1. Tenant `TenantTheme.defaultLocale` (admin Branding → Default Locale),
   if set and currently loaded.
2. Browser `Accept-Language` header — first loaded match wins.
3. `"en"`.

The tenant default beats `Accept-Language` deliberately: in the B2B SaaS
ICP, the workspace owner configures the language for their team's auth
pages, and individual users on differently-localized browsers should
still see the operator's chosen language. When the workspace has no
default set, `Accept-Language` is honored as the user's signal. There
is no per-user override and no `?lang=` query param in v1.7.2.
Operators expressed concern that a session-scoped override would muddy
audit-log review of multi-tenant flows; we can add it later behind a
tenant flag if real demand surfaces.

### `ViewContext` replaces theme + workspaceName parameters

A migrated view is called as `loginPage(slug, ctx, …)` where
`ctx: ViewContext` carries:

```kotlin
data class ViewContext(
    val theme: TenantTheme,
    val workspaceName: String,
    val locale: String,
    val translator: TranslationPort,
) {
    fun t(key: String, vararg args: Any?): String =
        translator.t(key, locale, *args)
}
```

This bundles the four things every auth view already needs into one
parameter, cutting the per-call ceremony for migration. A
`ViewContext.englishOnly(...)` factory exists for the few call sites
(tests, internal renders) that don't care about locale.

### Non-string keys (templates) fall back through the same path

Constants like `AUTH_PAGE_TITLE_LOGIN = "{0} | Sign In"` participate
fully — when a Spanish bundle omits this key, the English template is
substituted with the args (`"Acme | Sign In"`). The bundle isn't asked
to know about argument arity.

### Tenant default locale is stored on `TenantTheme`

`workspace_theme.default_locale VARCHAR(10) NULLABLE` (added in V37). The
admin Branding page renders a select listing only loaded locales, so a
configured locale always points at something real. Submissions that
reference an unloaded locale are silently dropped (validated against
`TranslationPort.availableLocales` in the route handler).

Placing it on `TenantTheme` rather than inventing a new
`tenant_localization_config` table reflects that locale is a
presentation concern, alongside logos/colors. Operators already think of
it under "branding."

### Migration is incremental

v1.7.2 ships with five auth pages migrated to `ctx.t()`:

- `loginPage`, `forgotPasswordPage`, `resetPasswordPage`,
  `acceptInvitePage`, `mfaChallengePage`.

Six remain hardcoded English and render in English regardless of the
active locale (`registerPage`, `magicLinkPage`, `magicLinkErrorPage`,
`forceChangePasswordPage`, `verifyEmailPage`, `socialRegistrationPage`).
The status is recorded at the top of `EnglishStrings.kt`. `PortalView`
and `AdminView` follow the existing "extract as you touch" policy from
the centralization initiative.

## Consequences

### Positive

- Zero new dependencies. `kotlinx.serialization` is already in the
  classpath; the bundle loader is ~80 lines including error handling.
- Operators ship translations like config: drop a JSON file, restart,
  done. No build, no rebuild, no plugin.
- Air-gapped installs and the quickstart Docker compose work unchanged
  because English is baked into the JAR.
- Translation contributors can work without a Kotlin toolchain — they
  edit JSON.
- The `ctx.t()` indirection means future infrastructure swaps (ICU
  MessageFormat, remote translation service, gRPC fanout) stay confined
  to a `TranslationPort` adapter swap.

### Negative — known limitations

1. **Pluralization is not handled.** `{0}` substitution is enough for
   what's currently in `EnglishStrings`, but Slavic languages and
   anything past trivial counts will outgrow it. Upgrade path is an
   ICU-backed `TranslationPort` adapter; current call sites won't
   change.
2. **Bundles are loaded once at startup.** Editing `es.json` requires a
   restart. We accept this — i18n is operator config, not user content,
   and runtime hot-reload would pull in file watchers and concurrency
   work for negligible gain.
3. **No locale switcher in the end-user UI.** Resolution is
   deterministic from `Accept-Language` + tenant default. A user on a
   French laptop hitting an Spanish-default tenant cannot pick English
   without changing their browser. We may add a tenant-level toggle
   later; for now the auto-resolution is enough for the reported needs.
4. **Six auth pages still render English regardless of locale.**
   Documented in `EnglishStrings.kt` and the `docs/i18n/README.md`.
   Migrating them is mechanical but not yet done — pulling i18n forward
   came at the cost of full coverage.
5. **No translation lifecycle tooling.** No "missing keys" report, no
   coverage badge, no checksums against an English baseline. We rely on
   the WARN log when a bundle ships unknown keys (logged once at
   startup) and on the silent English fallback at render time.

### Neutral

- `defaultLocale` lives on `TenantTheme`, which means any future move to
  per-environment locale defaults (different from per-tenant) needs a
  new field, likely on `EnvironmentConfig`. Acceptable given that
  per-tenant is the dominant case for SaaS deployments.
- `EnglishStrings.byKey` reflection runs once per JVM launch. It's not
  hot-pathed.

## Alternatives considered

- **Sidecar gettext / ICU translation service.** Strict separation but
  adds a process to deploy. Rejected: v1.7.2 needed to ship in a release
  cycle, not a quarter.
- **Embedded `messages_<locale>.properties` on the classpath.**
  Translations would have to be baked into the JAR, which means a
  rebuild per language. Rejected: contractual deal needs Spanish to
  ship without a custom build.
- **Database-backed translations.** Tenant overrides, runtime hot
  reload, audit trail — but every render becomes a DB query. Rejected
  as overkill for what is operator-supplied static text.
- **`kotauth-i18n` curated repo with a translation pipeline.**
  Worthwhile as a community project but a follow-up, not a blocker.
  Operators can currently grab `docs/i18n/es.json` directly.
- **Locale on `User` rather than tenant.** Personalizes well but
  requires user-facing locale-switcher UI to be useful, and risks
  per-tenant policy bypass (a user setting their locale to one no
  tenant has loaded). Rejected for v1.7.2; revisit when a tenant flag
  exists to permit it.
- **Embedding `defaultLocale` outside `TenantTheme` (e.g., a new
  `tenant_localization` table).** Cleaner boundary, but yields a single
  nullable column that is read in lock-step with the theme already on
  every auth page render. Rejected as schema-tax for no observable
  win.
