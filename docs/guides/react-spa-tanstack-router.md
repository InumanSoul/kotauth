# Integrating Kotauth with a React SPA and TanStack Router

TanStack-specific layer on top of the [browser-direct SPA guide](react-spa-direct.md). Read that first — it covers the Kotauth application registration, OIDC client config, `<AuthProvider>` wrapper, and the token-bearing fetch wrapper. This guide adds what TanStack Router needs: an `/auth/callback` route, a `beforeLoad` guard, and the router wiring.

**Stack:** React 18+, TanStack Router v1, [`oidc-client-ts`](https://github.com/authts/oidc-client-ts).

TanStack's `beforeLoad` runs outside React, so it can't call `react-oidc-context`'s `useAuth()` hook. The cleanest workaround is to construct the `UserManager` yourself and pass it to both `<AuthProvider>` and your `beforeLoad` helper. Replace the inline `oidcConfig` from the foundation guide with an exported `userManager`:

```ts
// src/auth/oidcConfig.ts
import { UserManager, WebStorageStateStore } from "oidc-client-ts";

export const userManager = new UserManager({
  authority: import.meta.env.VITE_KOTAUTH_AUTHORITY,
  client_id: import.meta.env.VITE_KOTAUTH_CLIENT_ID,
  redirect_uri: `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: window.location.origin,
  response_type: "code",
  scope: "openid profile email",
  userStore: new WebStorageStateStore({ store: window.localStorage }),
  automaticSilentRenew: true,
});
```

Pass it to `<AuthProvider userManager={userManager}>` in `main.tsx` and register `${origin}/auth/callback` on the Kotauth application.

---

## 1. Handle the auth callback

Create `src/routes/auth.callback.tsx` (or wherever your file-based routing places it):

```tsx
import { useEffect } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { userManager } from '../auth/oidcConfig'

export default function AuthCallbackPage() {
  const navigate = useNavigate()

  useEffect(() => {
    // oidc-client-ts parses the ?code= and ?state= query params,
    // exchanges the code for tokens at Kotauth's token endpoint,
    // and stores the resulting User object.
    userManager
      .signinRedirectCallback()
      .then(() => navigate({ to: '/' }))
      .catch(err => {
        console.error('Auth callback error:', err)
        navigate({ to: '/login' })
      })
  }, [navigate])

  return <p>Signing you in…</p>
}
```

Register this route at `/auth/callback` in your router (see section 3).

---

## 2. Build the `beforeLoad` guard

`beforeLoad` is the cleanest place to enforce authentication — it runs before the route component mounts and can redirect synchronously.

```ts
// src/auth/requireAuth.ts
import { redirect } from '@tanstack/react-router'
import { userManager } from './oidcConfig'

/**
 * Use this in beforeLoad for any route that requires a logged-in user.
 * If the user has no valid session, starts a login redirect automatically.
 */
export async function requireAuth() {
  const user = await userManager.getUser()

  if (!user || user.expired) {
    // Store the current path so we can restore it after login (optional)
    sessionStorage.setItem('auth:returnTo', window.location.pathname)
    await userManager.signinRedirect()
    // signinRedirect navigates away — this throw stops route loading
    throw redirect({ to: '/auth/callback' })
  }

  return { user }
}
```

---

## 3. Wire up the router

A minimal but complete router setup:

```tsx
// src/router.tsx
import {
  createRouter,
  createRootRoute,
  createRoute,
  Outlet,
} from '@tanstack/react-router'
import { requireAuth } from './auth/requireAuth'

import RootLayout        from './layouts/RootLayout'
import HomePage          from './routes/index'
import LoginPage         from './routes/login'
import AuthCallbackPage  from './routes/auth.callback'
import DashboardPage     from './routes/dashboard'
import ProfilePage       from './routes/profile'

// Root route — wraps everything with the AuthProvider
const rootRoute = createRootRoute({ component: RootLayout })

// Public routes
const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path:           '/login',
  component:      LoginPage,
})

const callbackRoute = createRoute({
  getParentRoute: () => rootRoute,
  path:           '/auth/callback',
  component:      AuthCallbackPage,
})

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path:           '/',
  component:      HomePage,
})

// Protected layout route — all children inherit the auth guard
const protectedRoute = createRoute({
  getParentRoute: () => rootRoute,
  id:             'protected',
  beforeLoad:     requireAuth,
  component:      () => <Outlet />,
})

const dashboardRoute = createRoute({
  getParentRoute: () => protectedRoute,
  path:           '/dashboard',
  component:      DashboardPage,
})

const profileRoute = createRoute({
  getParentRoute: () => protectedRoute,
  path:           '/profile',
  component:      ProfilePage,
})

const routeTree = rootRoute.addChildren([
  indexRoute,
  loginRoute,
  callbackRoute,
  protectedRoute.addChildren([
    dashboardRoute,
    profileRoute,
  ]),
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register { router: typeof router }
}
```

---

## 4. Root layout

Wrap the root in `<AuthProvider>` (sharing the same `userManager`):

```tsx
// src/layouts/RootLayout.tsx
import { Outlet } from "@tanstack/react-router";
import { AuthProvider } from "react-oidc-context";
import { userManager } from "../auth/oidcConfig";

export default function RootLayout() {
  return (
    <AuthProvider userManager={userManager}>
      <Outlet />
    </AuthProvider>
  );
}
```

---

## 5. Reading claims and roles

Inside route components, prefer `useAuth()` from `react-oidc-context` (the foundation guide covers this). For role-based UI:

```ts
const auth = useAuth();
const roles: string[] = (auth.user?.profile as any)?.realm_access?.roles ?? [];
const isAdmin = roles.includes("admin");
```

The `realm_access.roles` claim is populated automatically when the user has any role assignments in the workspace. Custom claim mappers can add tenant- or app-scoped role arrays.

---

## 6. Validating tokens on your backend

Backends validate access tokens with Kotauth's JWKS or introspection endpoint:

**JWKS** — recommended for stateless validation:

```
GET http://localhost:8080/t/<workspace>/protocol/openid-connect/certs
```

Any JWT library with JWKS support verifies the RS256 signature. The `iss` claim equals `${KAUTH_BASE_URL}/t/<workspace>`.

**Introspection** — for revocation awareness:

```
POST http://localhost:8080/t/<workspace>/protocol/openid-connect/introspect
Content-Type: application/x-www-form-urlencoded

token=<access_token>&client_id=<backend_client_id>&client_secret=<backend_secret>
```

---

## Silent SSO across apps

Kotauth honors the OIDC `prompt`, `max_age`, and `id_token_hint` parameters on `/authorize`. After the user signs into one client on your tenant, every subsequent `/authorize` against the same `/t/{slug}` path silent-auths via a server-side witness cookie — **no UI shown, no extra round-trip visible to the user**.

`oidc-client-ts` uses this automatically: `userManager.signinSilent()` and the iframe-based silent renew both hit `/authorize?prompt=none` under the hood. When the witness cookie is present, the response is a fresh authorization code; when it isn't, you get `error=login_required` and `oidc-client-ts` raises `ErrorResponse` for your error handler.

For SPA-side use cases:

- **`prompt=login`** — pass `prompt: "login"` to `signinRedirect({ extraQueryParams: { prompt: "login" } })` to force re-auth even if the user has a valid SSO cookie. Useful for "switch account" or "re-authenticate before sensitive action" flows.
- **`max_age`** — pass `max_age: "300"` to require a credential proof within the last 5 minutes. RPs gating sensitive operations (admin pages, payment authorization, etc.) typically pair this with the `auth_time` claim on the issued ID token. For MFA logins, `auth_time` is the moment the second factor was verified.
- **`prompt=select_account`** — same effect as `prompt=login` today. Will diverge once Kotauth ships an account picker.

**Logout that actually logs out.** Hitting `/protocol/openid-connect/logout` clears the silent-SSO cookie. Your `userManager.signoutRedirect()` call will fully sign the user out — they will see the login form on their next visit, not silent-auth back into the session.

---

## Troubleshooting

**`redirect_uri mismatch` error**
The redirect URI registered in Kotauth must exactly match what your app sends. Check for trailing slashes and port differences.

**`invalid_client` error**
Your application in Kotauth may be set to `confidential` (requires a client secret). Change it to `public` for SPAs.

**Silent refresh silently fails**
Silent renew uses a hidden iframe and requires third-party cookies to be allowed, which is increasingly blocked by browsers. If you hit this, use `checkSessionIframe: false` and implement refresh on tab focus instead, using `userManager.signinSilent()`.

**CORS errors on `/protocol/openid-connect/token`**
Kotauth's OIDC endpoints include CORS headers. If you're seeing CORS errors, check that `KAUTH_BASE_URL` matches the origin of your requests exactly.

---

## Where next

- [Browser-direct SPA foundation](react-spa-direct.md) — the OIDC config and `<AuthProvider>` this guide builds on
- [BFF pattern](react-bff-pattern.md) — production-grade alternative where tokens never reach the browser
- [Environment variable reference](../ENV_REFERENCE.md)
