# Frontend Component Guide

## Notification Patterns

Kotauth uses three distinct notification patterns. Each serves a different purpose — do not interchange them.

### 1. Toast — transient success feedback

**CSS:** `components/toast.css`
**JS:** `shared/toast.js`
**Trigger:** Server renders `data-toast-msg` on `<body>` after a `?saved=` redirect

Auto-dismissing notification that slides in at the top-right and fades out after 5 seconds. Used after successful form saves (settings, profile updates, password changes).

```
[✓ Settings saved.]     ← slides in, auto-removes after 5s
```

**When to use:** After any POST-redirect-GET that confirms a save. The server sets `data-toast-msg` on the body; the JS picks it up, shows the toast, and cleans the URL.

**When NOT to use:** For errors, warnings, or content that requires user action. Never for secrets or copy-able content.

**Implementation:** View passes `toastMessage` to `adminShell()` (admin) or sets `attributes["data-toast-msg"]` directly on `<body>` (portal). All messages live in `EnglishStrings`.

---

### 2. Alert — inline form feedback (auth + portal)

**CSS:** `shared/alert.css`
**Trigger:** Server renders the element in the page HTML

Persistent inline banner for form validation errors and success confirmations on tenant-branded pages (auth login, register, portal profile, portal MFA).

```
┌─────────────────────────────────────────┐
│ Invalid credentials. Please try again.  │  ← .alert .alert-error
└─────────────────────────────────────────┘
```

**Variants:**
- `.alert .alert-error` — red, for validation failures and auth errors
- `.alert .alert-success` — green, for confirmations (now mostly replaced by toast)
- `.alert .alert-warning` — amber, for warnings (e.g., MFA disable confirmation)

**When to use:** Inline feedback on auth/portal pages where the error relates to the form directly above or below it. The user needs to read the message and take action on the same page.

**When NOT to use:** For success feedback after a redirect (use toast instead). For admin console pages (use notice instead).

**Colors are hardcoded** — not tenant-brandable. Error red and success green must be legible regardless of the tenant's accent color.

---

### 3. Notice — admin console contextual banner

**CSS:** `components/notice.css`
**Trigger:** Server renders the element in the page HTML

Persistent banner with icon + body structure for admin console pages. Used for contextual warnings, errors, and success messages that require attention.

```
┌──────────────────────────────────────────────────────────┐
│ ⚠ SMTP is not configured. Email features are disabled.   │
│                                              [Configure] │
└──────────────────────────────────────────────────────────┘
```

**Variants:**
- `.notice` (default) — amber warning with icon
- `.notice .notice--success` — green with check icon
- `.notice .notice--error` — red with error icon

**Structure:**
```html
<div class="notice notice--success">
  <span class="notice__icon">✓</span>
  <div class="notice__body">
    <span class="notice__title">Profile saved.</span>
    <span class="notice__desc">Optional description.</span>
  </div>
  <a class="notice__link" href="...">Action</a>
</div>
```

**When to use:** Admin console warnings (SMTP not configured, lockout active), error feedback on admin forms, contextual information that persists on the page.

**When NOT to use:** For transient success feedback after saves (use toast instead). For auth/portal pages (use alert instead).

---

### Decision Matrix

| Scenario | Component | Bundle |
|---|---|---|
| Settings saved successfully | **Toast** | admin, portal |
| Invalid credentials on login | **Alert** (error) | auth |
| Password policy violation on register | **Alert** (error) | auth |
| SMTP not configured warning | **Notice** (warning) | admin |
| User profile edit validation error | **Notice** (error) | admin |
| Portal password change validation error | **Alert** (error) | portal |
| One-time secret display (copy-field) | **Notice** (success) + copy-field | admin |
| MFA setup completed | **Toast** | portal |

---

## CSS Architecture

### Layer pattern (button, form, alert)

Each shared component follows a three-layer pattern:

```
shared/<component>.css   ← base contract (tokens, transitions, shared selectors)
auth/<component>.css     ← re-exports shared + auth-specific defaults
portal/<component>.css   ← re-exports shared + portal-specific defaults
components/<component>.css ← admin-only (different token set, not tenant-branded)
```

**Auth layer** defaults to full-width block layout (form CTAs, card-based inputs).
**Portal layer** defaults to compact inline layout (action buttons, edit-field inputs).
**Admin layer** is independent — uses `tokens.css` not `toCssVars()`.

### Token sources

| Bundle | Brand tokens | Structural tokens |
|---|---|---|
| Admin | `base/tokens.css` (hardcoded) | `base/tokens-shared.css` (via tokens.css import) |
| Auth | `TenantTheme.toCssVars()` (runtime) | `base/tokens-shared.css` (build-time) |
| Portal | `TenantTheme.toCssVars()` (runtime) | `base/tokens-shared.css` (build-time) |
