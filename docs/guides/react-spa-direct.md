# Integrating Kotauth with a React SPA (Browser-Direct OIDC)

Add Kotauth authentication to a React SPA where **the browser talks to Kotauth directly** — Authorization Code + PKCE, tokens stored in the browser, `Authorization: Bearer` on every API request.

The patterns work with React Router, TanStack Router, or no router. For TanStack's `beforeLoad` integration, see the [TanStack Router guide](react-spa-tanstack-router.md) after reading this one.

**Stack:** React 18+, [`react-oidc-context`](https://github.com/authts/react-oidc-context) (wraps [`oidc-client-ts`](https://github.com/authts/oidc-client-ts)), Vite. Both libraries are framework-neutral OIDC implementations.

## When to use this pattern

Pick browser-direct OIDC when:

- The SPA is the only client talking to Kotauth — there is no separate backend doing user-facing work
- You want the simplest possible deployment: a static-hosted SPA + Kotauth, nothing in between
- You're comfortable with access tokens being reachable from JavaScript (the XSS trade-off)

Pick the [BFF pattern](react-bff-pattern.md) instead when:

- Tokens must never be reachable from JavaScript (high-value targets, regulated industries)
- You already have a backend that needs to authenticate calls (avoid double-handling identity)
- You want centrally-managed token refresh, not per-tab refresh in the browser

## Prerequisites

- Kotauth running locally (`docker compose up`) or on a server
- A workspace created in the Kotauth admin console (e.g. slug `my-app`)
- Node.js 18+

## 1. Register the SPA as an application in Kotauth

In the Kotauth admin console:

1. Navigate to your workspace → **Applications** → **New Application**
2. Set the name (e.g. `web-spa`)
3. Set **Access Type** to `public` — SPAs use PKCE, not a client secret
4. Under **Redirect URIs**, add:
   ```
   http://localhost:5173/
   https://app.yourdomain.com/
   ```
   The redirect URI is where Kotauth sends the user after login. `react-oidc-context` handles the callback in-place at the application root — no separate `/callback` route is needed unless you want one.
5. Under **Post-Logout Redirect URIs**, add the same origins
6. Copy the **Client ID** from the application detail page

## 2. Install dependencies

```bash
npm install oidc-client-ts react-oidc-context
```

`react-oidc-context` is the React wrapper; `oidc-client-ts` is the underlying OIDC protocol implementation. Both are framework-neutral and maintained by the OpenID Foundation Authts working group.

## 3. Configure the OIDC client

Create `src/auth/oidcConfig.ts`:

```ts
import { WebStorageStateStore } from "oidc-client-ts";
import type { AuthProviderProps } from "react-oidc-context";

export const oidcConfig: AuthProviderProps = {
  authority: import.meta.env.VITE_KOTAUTH_AUTHORITY,
  client_id: import.meta.env.VITE_KOTAUTH_CLIENT_ID,
  redirect_uri: import.meta.env.VITE_KOTAUTH_REDIRECT_URI,
  response_type: "code",
  scope: "openid profile email",
  userStore: new WebStorageStateStore({ store: window.localStorage }),
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};
```

| Key | What it does |
|---|---|
| `authority` | Kotauth issuer URL. The library fetches `${authority}/.well-known/openid-configuration` and auto-discovers all endpoints. For a single-workspace Kotauth, this is `https://auth.example.com`; for a multi-workspace deployment, point at `https://auth.example.com/t/<workspace-slug>`. |
| `client_id` | The public client ID you copied in step 1. |
| `redirect_uri` | Where Kotauth sends the user after login. Must exactly match one of the URIs registered on the application. |
| `response_type: "code"` | Authorization Code flow. PKCE is enabled automatically by `oidc-client-ts` for public clients. |
| `scope` | `openid` is required; `profile` and `email` request the corresponding ID-token claims. Add custom scopes you defined on the application. |
| `userStore` | Where access tokens and ID tokens live. `localStorage` survives a tab close; `sessionStorage` does not. Both are JavaScript-accessible — see [security trade-offs](#security-trade-offs). |
| `onSigninCallback` | Strips the `?code=...&state=...` query params after a successful login so the URL is clean. |

Environment variables in `.env`:

```env
VITE_KOTAUTH_AUTHORITY=http://localhost:8080/t/my-app
VITE_KOTAUTH_CLIENT_ID=web-spa
VITE_KOTAUTH_REDIRECT_URI=http://localhost:5173/
```

## 4. Wrap the app with the auth provider

In `src/main.tsx`:

```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AuthProvider } from "react-oidc-context";
import { oidcConfig } from "./auth/oidcConfig";
import { RequireAuth } from "./auth/RequireAuth";
import { App } from "./App";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <AuthProvider {...oidcConfig}>
      <RequireAuth>
        <App />
      </RequireAuth>
    </AuthProvider>
  </StrictMode>,
);
```

`<AuthProvider>` exposes the `useAuth()` hook anywhere inside the tree. `<RequireAuth>` (defined next) gates the protected portion of the app.

## 5. Gate the app with `<RequireAuth>`

Create `src/auth/RequireAuth.tsx`:

```tsx
import { type ReactNode } from "react";
import { useAuth } from "react-oidc-context";
import { Login } from "./Login";

export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth();

  if (auth.isLoading) {
    return <p>Signing you in…</p>;
  }

  if (auth.error) {
    return <p>Sign-in failed: {auth.error.message}</p>;
  }

  if (!auth.isAuthenticated) {
    return <Login />;
  }

  return <>{children}</>;
}
```

And `src/auth/Login.tsx`:

```tsx
import { useAuth } from "react-oidc-context";

export function Login() {
  const auth = useAuth();
  return (
    <button onClick={() => void auth.signinRedirect()}>
      Sign in
    </button>
  );
}
```

`auth.signinRedirect()` navigates to Kotauth's `/oauth2/authorize` with PKCE. On the return, `react-oidc-context` exchanges the code for tokens using the stored PKCE verifier and sets `auth.isAuthenticated = true` — no separate `/callback` route needed because `redirect_uri` points at the app root.

## 6. Attach the access token to API requests

Create an API client that pulls the token from the auth hook on every render:

```ts
// src/api/useClient.ts
import { useAuth } from "react-oidc-context";
import { useMemo } from "react";
import { ApiClient } from "./client";

export function useClient(): ApiClient | null {
  const auth = useAuth();
  const token = auth.user?.access_token;
  return useMemo(() => (token ? new ApiClient(token) : null), [token]);
}
```

```ts
// src/api/client.ts
const API_BASE = import.meta.env.VITE_API_URL;

export class ApiClient {
  constructor(private readonly token: string) {}

  async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const res = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${this.token}`,
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...init.headers,
      },
    });
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
    return res.json() as Promise<T>;
  }
}
```

Usage from a component:

```tsx
function Profile() {
  const client = useClient();
  const { data } = useQuery({
    queryKey: ["profile"],
    queryFn: () => client!.request<UserProfile>("/me"),
    enabled: client !== null,
  });
  return <p>{data?.email}</p>;
}
```

The `useMemo` rebuilds the client when the token changes, keeping every request bound to the current token.

## 7. Logout

```tsx
function Logout() {
  const auth = useAuth();
  return (
    <button onClick={() => void auth.signoutRedirect()}>
      Sign out
    </button>
  );
}
```

`auth.signoutRedirect()` navigates to Kotauth's `/oauth2/logout` (clears the SSO cookie), then redirects to the post-logout URI; the library clears local OIDC state. For local-only logout (clear tokens but keep the Kotauth SSO session), use `auth.removeUser()`.

## 8. Token refresh

To refresh expired tokens transparently, enable silent renew in `oidcConfig`:

```ts
export const oidcConfig: AuthProviderProps = {
  // ... existing fields
  automaticSilentRenew: true,
  // The library spawns a hidden iframe at this URL to do `prompt=none` re-auth.
  // Default is fine; override only if you need a dedicated route.
  silent_redirect_uri: import.meta.env.VITE_KOTAUTH_REDIRECT_URI,
};
```

Silent renew uses a hidden iframe pointed at Kotauth with `prompt=none`. If the user still has an active SSO session, Kotauth issues a fresh code without UI; the library exchanges it for a new access token. If the SSO session has expired, the iframe returns an error and `react-oidc-context` surfaces it on `auth.error` — at which point you redirect to login.

Silent renew has known limitations in browsers that block third-party cookies (Safari, Firefox in private mode). For those, the refresh token grant is the fallback — set `scope: "openid profile email offline_access"` in `oidcConfig` to request a refresh token, and the library will use it instead of the iframe.

## Security trade-offs

Browser-direct OIDC stores the access token in `localStorage` (or `sessionStorage`). Any JavaScript running on the page can read it. This means:

- **An XSS vulnerability becomes a token-theft vulnerability.** Defense-in-depth: ship a strict CSP that blocks inline scripts, audit third-party packages, treat any DOM injection sink (`innerHTML`, `dangerouslySetInnerHTML`) as a security review surface.
- **The refresh token, if used, also lives in storage.** Same risk.
- **Token revocation requires both Kotauth-side cleanup and a local clear.** `signoutRedirect()` does both; a logout that only clears Kotauth (or only clears local) creates a stale state.

The BFF pattern eliminates these by keeping tokens off the browser entirely. If your threat model includes "attacker exfiltrates user tokens via XSS," prefer [BFF](react-bff-pattern.md).

## Common issues

| Symptom | Cause | Fix |
|---|---|---|
| `redirect_uri_mismatch` from Kotauth | The redirect URI in `oidcConfig` doesn't exactly match what's registered on the application | Update the application in the admin console; URIs are matched character-for-character including trailing slashes |
| `auth.isAuthenticated` flips to `false` on every page load | Tokens are in `sessionStorage` and a new tab starts a fresh session | Use `localStorage` for cross-tab persistence (the default in this guide) |
| `?code=...` stays in the URL after login | Missing `onSigninCallback` in `oidcConfig` | Add the `window.history.replaceState` snippet from step 3 |
| API calls return 401 after a few minutes | Access token expired and silent renew isn't enabled | Set `automaticSilentRenew: true` in `oidcConfig` |
| Silent renew fails in Safari | Third-party cookie blocking breaks the iframe flow | Add `offline_access` to `scope` so the library uses the refresh token grant instead |
| Multi-workspace deployment hits the wrong tenant | `authority` is set to the base URL, not the tenant-scoped issuer | Set `authority` to `${KOTAUTH_URL}/t/${WORKSPACE_SLUG}` |

## Where next

- [TanStack Router integration](react-spa-tanstack-router.md) — `beforeLoad` route guards on top of this guide
- [BFF pattern](react-bff-pattern.md) — same flow, but tokens never reach the browser
- [Environment variable reference](../ENV_REFERENCE.md) — every variable Kotauth reads at startup
