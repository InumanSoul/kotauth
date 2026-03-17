# Integrating Kotauth with a React SPA and TanStack Router

This guide walks through adding Kotauth authentication to a React single-page application using [TanStack Router](https://tanstack.com/router). The result is a complete auth flow: login redirect, callback handling, protected routes, token refresh, and logout.

**What you'll build:**
- OIDC Authorization Code + PKCE flow (the correct flow for SPAs — no client secret needed)
- Auth context that holds the user session and exposes `login`, `logout`, and `getAccessToken`
- Route-level protection using TanStack Router's `beforeLoad` hook
- Silent token refresh before expiry

**Stack:** React 18, TanStack Router v1, [`oidc-client-ts`](https://github.com/authts/oidc-client-ts)

---

## Prerequisites

- Kotauth running locally (`docker compose up`) or on a server
- A workspace created in the admin console (e.g. slug `my-app`)
- Node.js 18+

---

## 1. Create an Application in Kotauth

In the Kotauth admin console:

1. Navigate to your workspace → **Applications** → **New Application**
2. Set the name (e.g. `react-frontend`)
3. Under **Redirect URIs**, add:
   ```
   http://localhost:5173/auth/callback
   ```
4. Under **Post-Logout Redirect URIs**, add:
   ```
   http://localhost:5173
   ```
5. Set **Access Type** to `public` (no client secret — correct for SPAs)
6. Save. Copy the **Client ID** shown on the application detail page.

---

## 2. Install Dependencies

```bash
npm install oidc-client-ts
```

TanStack Router should already be in your project. If not:

```bash
npm install @tanstack/react-router
```

---

## 3. Configure the OIDC Client

Create `src/auth/oidcConfig.ts`:

```ts
import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

const KOTAUTH_BASE_URL = import.meta.env.VITE_KOTAUTH_URL ?? 'http://localhost:8080'
const WORKSPACE_SLUG   = import.meta.env.VITE_KOTAUTH_WORKSPACE ?? 'my-app'
const CLIENT_ID        = import.meta.env.VITE_KOTAUTH_CLIENT_ID ?? 'react-frontend'

export const userManager = new UserManager({
  // The authority is your workspace's OIDC issuer URL.
  // oidc-client-ts fetches /.well-known/openid-configuration from this URL
  // to auto-discover all endpoints (token, userinfo, logout, etc.).
  authority: `${KOTAUTH_BASE_URL}/t/${WORKSPACE_SLUG}`,

  client_id:     CLIENT_ID,
  redirect_uri:  `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: window.location.origin,

  // Scopes: 'openid' is required. 'profile' and 'email' add standard claims.
  // Add any custom scopes you defined on the application.
  scope: 'openid profile email',

  // Authorization Code + PKCE — the only correct flow for SPAs.
  // oidc-client-ts handles code_verifier/code_challenge generation automatically.
  response_type: 'code',

  // Store the user session in sessionStorage so it's cleared on tab close.
  // Use localStorage if you need persistence across tabs — understand the
  // XSS risk trade-off before choosing.
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),

  // Trigger a silent token refresh 60 seconds before the access token expires.
  automaticSilentRenew: true,
  accessTokenExpiringNotificationTimeInSeconds: 60,
})
```

Add the environment variables to your `.env.local`:

```
VITE_KOTAUTH_URL=http://localhost:8080
VITE_KOTAUTH_WORKSPACE=my-app
VITE_KOTAUTH_CLIENT_ID=react-frontend
```

---

## 4. Build the Auth Context

Create `src/auth/AuthContext.tsx`:

```tsx
import { createContext, useContext, useEffect, useState, useCallback } from 'react'
import type { User } from 'oidc-client-ts'
import { userManager } from './oidcConfig'

interface AuthContextValue {
  user:           User | null
  isLoading:      boolean
  login:          () => Promise<void>
  logout:         () => Promise<void>
  getAccessToken: () => string | null
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser]         = useState<User | null>(null)
  const [isLoading, setLoading] = useState(true)

  useEffect(() => {
    // Load any existing session from storage on mount
    userManager.getUser().then(u => {
      setUser(u)
      setLoading(false)
    })

    // Keep local state in sync when oidc-client-ts silently renews tokens
    const onUserLoaded   = (u: User) => setUser(u)
    const onUserUnloaded = ()        => setUser(null)

    userManager.events.addUserLoaded(onUserLoaded)
    userManager.events.addUserUnloaded(onUserUnloaded)

    return () => {
      userManager.events.removeUserLoaded(onUserLoaded)
      userManager.events.removeUserUnloaded(onUserUnloaded)
    }
  }, [])

  const login = useCallback(() =>
    // Redirects to Kotauth login page. After login, Kotauth redirects back
    // to /auth/callback with the authorization code.
    userManager.signinRedirect(),
  [])

  const logout = useCallback(() =>
    // Redirects to Kotauth's end_session endpoint, which clears the server-side
    // session and redirects back to post_logout_redirect_uri.
    userManager.signoutRedirect(),
  [])

  const getAccessToken = useCallback(() =>
    user?.access_token ?? null,
  [user])

  return (
    <AuthContext.Provider value={{ user, isLoading, login, logout, getAccessToken }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
```

---

## 5. Handle the Auth Callback

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

Register this route at `/auth/callback` in your router (see step 7).

---

## 6. Create an Auth Guard Utility

TanStack Router's `beforeLoad` is the cleanest place to enforce authentication. Create a reusable helper:

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

## 7. Wire Up the Router

Here's a minimal but complete router setup:

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

// Root route — wraps everything with the AuthProvider (see step 8)
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

## 8. Set Up the Root Layout

The `AuthProvider` goes in your root layout so every route can access auth state:

```tsx
// src/layouts/RootLayout.tsx
import { Outlet } from '@tanstack/react-router'
import { AuthProvider } from '../auth/AuthContext'

export default function RootLayout() {
  return (
    <AuthProvider>
      <Outlet />
    </AuthProvider>
  )
}
```

---

## 9. Use Auth State in Components

```tsx
// src/routes/dashboard.tsx
import { useAuth } from '../auth/AuthContext'

export default function DashboardPage() {
  const { user, logout, getAccessToken } = useAuth()

  async function fetchProtectedData() {
    const token = getAccessToken()
    const res = await fetch('/api/whatever', {
      headers: { Authorization: `Bearer ${token}` },
    })
    return res.json()
  }

  return (
    <div>
      <h1>Welcome, {user?.profile.name}</h1>
      <p>Email: {user?.profile.email}</p>
      <button onClick={logout}>Sign out</button>
    </div>
  )
}
```

**Getting token claims:** The `user.profile` object contains the OIDC claims from Kotauth's userinfo endpoint — `sub`, `email`, `email_verified`, `name`, `preferred_username`. The `user.access_token` is a JWT you can send to your backend APIs.

**Role-based UI:** Roles and groups from Kotauth appear in the JWT under `realm_access.roles`. You can read them from the decoded token, but for UI decisions it's cleaner to get them from the userinfo profile:

```ts
// oidc-client-ts includes all claims Kotauth returns in user.profile
const roles: string[] = (user?.profile as any)?.realm_access?.roles ?? []
const isAdmin = roles.includes('admin')
```

---

## 10. Calling Kotauth's API from Your Backend

Your backend (or BFF) can validate access tokens using Kotauth's JWKS endpoint or introspection endpoint.

**JWKS (recommended for stateless validation):**

```
GET http://localhost:8080/t/my-app/protocol/openid-connect/certs
```

Use any JWT library that supports JWKS to verify the `RS256` signature. The `iss` claim will be `http://localhost:8080/t/my-app`.

**Introspection (for opaque token checking or forced revocation awareness):**

```
POST http://localhost:8080/t/my-app/protocol/openid-connect/introspect
Content-Type: application/x-www-form-urlencoded

token=<access_token>&client_id=<backend_client_id>&client_secret=<backend_secret>
```

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

## What's Next

- [Environment Variable Reference](../ENV_REFERENCE.md)
- [REST API Reference](http://localhost:8080/t/my-app/api/v1/docs) — Swagger UI
- Webhook setup — receive real-time events (`user.created`, `login.success`, etc.) in your backend
