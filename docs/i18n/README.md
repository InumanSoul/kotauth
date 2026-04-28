# Translation bundles

KotAuth's auth pages are server-rendered HTML. English is baked into the JAR and
is always available. Other locales are **opt-in** via volume-mounted JSON
bundles — set `KAUTH_I18N_BUNDLE_DIR` to a directory containing one
`<locale>.json` file per language and KotAuth loads them at startup.

## How it works

- Each `<locale>.json` is a flat key→string map keyed by the field names in
  `EnglishStrings.kt` (e.g. `"LOGIN_SUBMIT": "Iniciar sesión"`).
- Templates use positional placeholders: `{0}`, `{1}`, … Substitution happens
  at render time. Example: `"AUTH_PAGE_TITLE_LOGIN": "{0} | Iniciar sesión"`.
- Missing keys fall back to the English value baked into the JAR — bundles
  don't need to translate every key.
- An `en.json` file in the bundle dir is **ignored with a warning**. The JAR's
  English source of truth is authoritative.
- A bundle that fails JSON parsing or contains a non-string value is skipped
  with a warning. Other bundles still load.

## Locale resolution per request

1. The `Accept-Language` header (first loaded match wins).
2. The tenant's `defaultLocale` (Branding → Default Locale in the admin UI),
   if it points to a loaded locale.
3. `"en"`.

## What's covered in v1.7.2

The Spanish bundle (`es.json`) ships as a sample and covers the auth pages
that have been migrated to the i18n pipeline:

- `loginPage`
- `forgotPasswordPage`
- `resetPasswordPage`
- `acceptInvitePage`
- `mfaChallengePage`

Pages **not yet migrated** render in English regardless of the active locale:

- `registerPage`
- `magicLinkPage`
- `magicLinkErrorPage`
- `forceChangePasswordPage`
- `verifyEmailPage`
- `socialRegistrationPage`

These will be migrated incrementally — see `EnglishStrings.kt` for the
current status.

## Deploying a bundle

1. Mount a directory into the container, e.g.:

   ```yaml
   services:
     kotauth:
       volumes:
         - ./i18n:/etc/kotauth/i18n:ro
       environment:
         KAUTH_I18N_BUNDLE_DIR: /etc/kotauth/i18n
   ```

2. Drop one or more `<locale>.json` files into that directory. The
   `es.json` in this folder is a starting point — copy it, translate the
   missing keys, and you're done.

3. Restart KotAuth. Loaded locales appear in the admin Branding page's
   **Default Locale** dropdown.

## Adding a new language

1. Copy `es.json` to `<locale>.json` (e.g. `fr.json`, `de.json`,
   `pt-br.json`) in your bundle directory.
2. Translate each value, leaving the keys unchanged.
3. Keep `{0}`, `{1}` placeholders in their original positions — they map to
   runtime values like the workspace name or the configured password
   minimum length.
4. Restart KotAuth.
