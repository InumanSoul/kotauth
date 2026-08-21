# Integrating Kotauth with a React SPA (BFF Pattern)

Add Kotauth authentication to a React SPA using the **Backend-For-Frontend (BFF) pattern**: a thin backend handles the OIDC token exchange, stores access and refresh tokens server-side, and exposes session-cookie-authenticated endpoints to the SPA. The browser never sees a bearer token.

This is the **recommended pattern for production deployments**. If an XSS lands, the attacker cannot exfiltrate tokens — only an opaque `HTTP-only` session cookie that the browser will not surface to script.

**Stack:** React 18+, Vite (dev proxy), any backend that can speak OIDC. The backend example below is Kotlin/Ktor (Kotauth's native stack), but the pattern translates to Express, FastAPI, ASP.NET Core, Spring Boot, anything that can do an Authorization Code + PKCE exchange and set a cookie.

## When to use this pattern

Use BFF when any of these are true:

- The app is customer-facing or regulated; XSS-driven token theft is unacceptable
- You already run a backend that calls internal APIs on the user's behalf
- You want centrally-managed token refresh (one server-side flow, not N tabs)
- You need extra authorization, rate limiting, or audit logging at the gateway

For small internal tools with no backend, the [browser-direct SPA pattern](react-spa-direct.md) is simpler.

## The architecture

```
┌──────────┐     ┌──────────┐     ┌─────────┐
│  Browser │────▶│   BFF    │────▶│ Kotauth │
│  (SPA)   │     │ (Kotlin) │     │         │
└──────────┘     └──────────┘     └─────────┘
     │                 │
     │  HTTP-only      │   Sessions in
     │  session cookie │   Redis (TTL-bounded)
     ▼                 ▼
```

- The browser holds an **opaque session ID** in an HTTP-only cookie
- The BFF holds the **access and refresh tokens**, keyed by that session ID, in Redis
- The BFF forwards downstream API calls with `Authorization: Bearer <access_token>` and refreshes transparently when the access token nears expiry
- Login is a browser navigation to `/auth/login` (not a programmatic SDK call); logout is `POST /auth/logout` then a navigation

## Prerequisites

- Kotauth running locally or on a server
- A workspace created in Kotauth with an application registered as **`confidential`** (BFF clients have a backend, so they have a real client secret)
- A backend you can deploy alongside the SPA — this guide uses Kotlin/Ktor; the patterns translate directly
- Redis available to the BFF (for server-side session storage)

## 1. Register the BFF as a confidential application

In the Kotauth admin console:

1. Navigate to your workspace → **Applications** → **New Application**
2. Set **Access Type** to `confidential` — the BFF holds a client secret server-side
3. Under **Redirect URIs**, add:
   ```
   http://localhost:5173/api/auth/callback
   https://app.yourdomain.com/api/auth/callback
   ```
   The callback is served by the BFF, not the SPA. In dev, Vite proxies `/api/*` to the BFF (see step 4), so the URL host is the SPA host.
4. Under **Post-Logout Redirect URIs**, add:
   ```
   http://localhost:5173/
   https://app.yourdomain.com/
   ```
5. Copy the **Client ID** and **Client Secret** shown on the confirmation banner — the BFF needs both, and the secret is shown only once. If you lose it, use **Regenerate Secret** on the application page to issue a new one.

## 2. The SPA side

The SPA does **not** install an OIDC library. It only needs `fetch` + state management.

### Vite proxy config

The SPA dev server proxies `/api/*` to the BFF so cookies flow on the same origin without CORS:

```ts
// vite.config.ts
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? "http://localhost:8088";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": { target: apiProxyTarget, changeOrigin: false },
    },
  },
});
```

`changeOrigin: false` preserves the browser's `Origin` header so the BFF's same-origin check works during dev. In production, Caddy/nginx/Traefik does the same routing — the SPA and BFF live on the same hostname.

### Fetch wrapper

Every API call rides the session cookie:

```ts
// src/lib/api.ts
const LOGIN_URL = "/api/auth/login";

let onUnauthenticated = () => {
  if (typeof window !== "undefined") {
    window.location.assign(LOGIN_URL);
  }
};

export function setUnauthenticatedHandler(handler: () => void): void {
  onUnauthenticated = handler;
}

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: "include",
    headers: {
      Accept: "application/json",
      ...(init.body ? { "Content-Type": "application/json" } : {}),
      ...init.headers,
    },
  });

  if (response.status === 401) {
    onUnauthenticated();
    throw new ApiError(401, "Not authenticated");
  }

  if (!response.ok) {
    throw new ApiError(response.status, `${response.status} ${response.statusText}`);
  }

  return response.json() as Promise<T>;
}
```

`credentials: 'include'` sends the session cookie on every same-origin request. The SPA never sets `Authorization` because it has no token. 401s route through one handler that hard-navigates to `/api/auth/login`.

### Session query

Use TanStack Query (or your state library of choice) to fetch the current session on mount:

```ts
// src/auth/session.ts
import { apiFetch } from "../lib/api";

export interface SessionIdentity {
  sub: string;
  email: string;
  name?: string;
  scopes: string[];
}

export async function fetchSession(): Promise<SessionIdentity | null> {
  try {
    return await apiFetch<SessionIdentity>("/api/auth/me");
  } catch (e) {
    if (e instanceof Error && (e as { status?: number }).status === 401) {
      return null;
    }
    throw e;
  }
}
```

```tsx
// src/auth/useSession.ts
import { useQuery } from "@tanstack/react-query";
import { fetchSession } from "./session";

export function useSession() {
  return useQuery({
    queryKey: ["session"],
    queryFn: fetchSession,
    retry: (failureCount, error) => {
      if (error instanceof ApiError && error.status === 401) return false;
      return failureCount < 2;
    },
    staleTime: 60_000,
  });
}
```

### Route protection

Two options. Pick by app shape.

**Implicit (admin-style):** the root layout requires a session; if 401 lands, `onUnauthenticated` triggers and the SPA hard-navigates to `/api/auth/login`.

```tsx
// src/App.tsx
import { useSession } from "./auth/useSession";

export function App() {
  const session = useSession();
  if (session.isPending) return <p>Loading…</p>;
  if (!session.data) return null; // 401 handler already redirected
  return <Shell user={session.data}>{/* protected app */}</Shell>;
}
```

**Explicit (public-style):** wrap routes that need auth in a `<SessionGate>` so unauthenticated users see a splash before the redirect:

```tsx
// src/auth/SessionGate.tsx
import { type ReactNode, useEffect } from "react";
import { useSession } from "./useSession";

export function SessionGate({ children }: { children: ReactNode }) {
  const session = useSession();

  useEffect(() => {
    if (session.isError || session.data === null) {
      window.location.assign("/api/auth/login");
    }
  }, [session.isError, session.data]);

  if (session.isPending || !session.data) return <p>Redirecting to sign-in…</p>;
  return <>{children}</>;
}
```

### Logout

```ts
// src/auth/logout.ts
import { apiFetch } from "../lib/api";

export async function logout(): Promise<void> {
  await apiFetch<{ ok: boolean }>("/api/auth/logout", { method: "POST" });
  window.location.assign("/api/auth/login?prompt=login");
}
```

`POST /api/auth/logout` clears the server-side session and the cookie; `prompt=login` forces re-auth on the next navigation even if the Kotauth SSO cookie is still valid.

## 3. The BFF side

Four endpoints. The Kotlin/Ktor sketch below mirrors what Kotauth itself runs.

### Configuration

```kotlin
data class BffConfig(
    val kotauthBaseUrl: String,
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val postLoginRedirect: String,
    val postLogoutRedirect: String,
    val cookieSecure: Boolean,
    val allowedOrigins: List<String>,
)
```

### `GET /auth/login` — start the flow

```kotlin
get("/auth/login") {
    val state = secureRandomBase64(32)
    val codeVerifier = secureRandomBase64(64)
    val codeChallenge = sha256Base64Url(codeVerifier)

    sessionStore.savePending(state, codeVerifier, ttlSeconds = 300)

    val authorizeUrl = URLBuilder("${config.kotauthBaseUrl}/oauth2/authorize").apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", config.clientId)
        parameters.append("redirect_uri", config.redirectUri)
        parameters.append("scope", "openid profile email offline_access")
        parameters.append("state", state)
        parameters.append("code_challenge", codeChallenge)
        parameters.append("code_challenge_method", "S256")
        call.request.queryParameters["prompt"]?.let { parameters.append("prompt", it) }
    }.buildString()

    call.respondRedirect(authorizeUrl, permanent = false)
}
```

### `GET /auth/callback` — exchange code for tokens

```kotlin
get("/auth/callback") {
    val state = call.request.queryParameters["state"]
        ?: return@get call.respond(HttpStatusCode.BadRequest)
    val code = call.request.queryParameters["code"]
        ?: return@get call.respond(HttpStatusCode.BadRequest)

    val pending = sessionStore.takePending(state)
        ?: return@get call.respond(HttpStatusCode.BadRequest, "state mismatch")

    val tokens = kotauthClient.exchangeCode(
        code = code,
        codeVerifier = pending.codeVerifier,
        redirectUri = config.redirectUri,
        clientId = config.clientId,
        clientSecret = config.clientSecret,
    )

    val identity = kotauthClient.fetchUserInfo(tokens.accessToken)
    val sessionId = sessionStore.create(
        accessToken = tokens.accessToken,
        refreshToken = tokens.refreshToken,
        accessTokenExpiresAt = tokens.expiresAt,
        identity = identity,
    )

    call.setSessionCookie(sessionId, config.cookieSecure)
    call.respondRedirect(config.postLoginRedirect, permanent = false)
}

fun ApplicationCall.setSessionCookie(sessionId: String, secure: Boolean) {
    response.cookies.append(
        Cookie(
            name = "bff_session",
            value = sessionId,
            maxAge = 60 * 60 * 24 * 7,
            path = "/",
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}
```

`httpOnly` keeps the cookie unreachable from JavaScript; `SameSite=Lax` blocks cross-site CSRF on state-changing requests; `secure` is required in production.

### `GET /auth/me` — return the current identity

```kotlin
get("/auth/me") {
    val sessionId = call.request.cookies["bff_session"]
        ?: return@get call.respond(HttpStatusCode.Unauthorized)
    val session = sessionStore.get(sessionId)
        ?: return@get call.respond(HttpStatusCode.Unauthorized)

    if (session.accessTokenExpiresAt.isBefore(Instant.now())) {
        sessionStore.refresh(sessionId, kotauthClient)
    }

    call.respond(
        HttpStatusCode.OK,
        MeDto(
            sub = session.identity.sub,
            email = session.identity.email,
            name = session.identity.name,
            scopes = session.scopes,
        ),
    )
}
```

Refresh happens on the BFF; the SPA never sees an expired token during normal use.

### `POST /auth/logout` — clear session

```kotlin
post("/auth/logout") {
    if (!call.requireSameOrigin(config.allowedOrigins)) return@post
    val sessionId = call.request.cookies["bff_session"]
    sessionId?.let { sessionStore.delete(it) }
    call.clearSessionCookie(config.cookieSecure)
    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
}

suspend fun ApplicationCall.requireSameOrigin(allowed: List<String>): Boolean {
    val origin = request.headers["Origin"] ?: request.headers["Referer"]
    if (origin == null || allowed.none { origin.startsWith(it) }) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "csrf-rejected"))
        return false
    }
    return true
}
```

Notice the CSRF defense: **Origin/Referer validation**, not a custom token header. Because the SPA and BFF are same-origin (production) or proxied through the same Vite dev server (development), the browser sends `Origin` automatically. Cross-site POSTs from an attacker page lack the right `Origin` and get a 403. No double-submit cookie, no `<meta name="csrf-token">`, no header to forget.

### Forwarding API calls (optional)

If your BFF also proxies downstream API calls (e.g. to a separate microservice), inject the access token from the server-side session:

```kotlin
get("/api/orders/{id}") {
    val session = requireSession() ?: return@get
    val response = httpClient.get("$ordersApiUrl/${call.parameters["id"]}") {
        header("Authorization", "Bearer ${session.accessToken}")
    }
    call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
}
```

The SPA only ever sees opaque cookie auth. The bearer token lives in BFF memory and Redis.

## 4. Production deployment

Two services behind one origin:

```
https://app.yourdomain.com/         → SPA static files (Caddy/nginx)
https://app.yourdomain.com/api/*    → BFF (proxied)
```

Caddy snippet:

```caddyfile
app.yourdomain.com {
    handle /api/* {
        reverse_proxy bff:8080
    }
    handle {
        root * /var/www/spa
        try_files {path} /index.html
        file_server
    }
}
```

The BFF stays internal; only the SPA origin is publicly exposed. Set `cookieSecure = true` and `SameSite=Lax` in production.

## What this pattern gives you (security)

| Concern | Browser-direct SPA | BFF |
|---|---|---|
| Access token reachable from JS | Yes — `localStorage` or `sessionStorage` | **No** — server-side only |
| XSS leads to token theft | Yes | **No** — attacker can only ride the session via the existing cookie origin |
| PKCE verifier handling | Browser stores it in `sessionStorage` | Server stores it in Redis (per-state, short TTL) |
| Refresh token handling | Browser holds it (if `offline_access` is granted) | Server-side refresh, transparent to the SPA |
| Token expiry UX | SPA must detect, decide silent renew vs. logout | SPA never sees an expired token |
| Logout completeness | SPA must clear local storage + call Kotauth | One `POST /auth/logout`, both layers cleared |
| CSRF defense | Bearer in `Authorization` header is naturally CSRF-immune | Origin/Referer validation on state-changing endpoints |
| Multi-tab consistency | Per-tab token state; refresh races possible | One session, all tabs in sync |

The cost is an extra hop per API call and a backend you have to run, monitor, and deploy. For anything beyond a small internal tool, that cost is the right trade.

## Common issues

| Symptom | Cause | Fix |
|---|---|---|
| `credentials: 'include'` requests don't send the cookie | The SPA and BFF are on different origins and the BFF doesn't set `Access-Control-Allow-Credentials: true` | Either proxy `/api/*` through the SPA origin (recommended), or set proper CORS headers + `SameSite=None; Secure` on the cookie |
| Login redirects loop | `Origin` header from the post-login redirect doesn't match `allowedOrigins` | Verify the BFF's `allowedOrigins` includes the SPA's exact origin |
| Cookie not set in dev | Vite proxy is dropping `Set-Cookie` | Ensure `changeOrigin: false` in the Vite proxy config |
| `POST /auth/logout` returns 403 | CSRF check rejected the request | The SPA must POST from the same origin; check the `Origin` header in dev tools |
| Sessions disappear on BFF restart | Sessions are in memory, not Redis | Wire up a Redis-backed session store (the example assumes Redis) |

## Where next

- [Browser-direct SPA pattern](react-spa-direct.md) — the simpler alternative without a backend
- [TanStack Router integration](react-spa-tanstack-router.md) — `beforeLoad` route guards (composable with this pattern's session query)
- [Production deployment](../deploy/production.md) — Kotauth in production
