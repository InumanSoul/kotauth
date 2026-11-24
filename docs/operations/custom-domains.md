# A public domain per workspace

One Kotauth instance can serve several products. By default every workspace lives on the instance's
own address (`KAUTH_BASE_URL`, for example `https://sso.example.com/t/acme`). A workspace can
instead be given its own public domain, such as `https://auth.acme.com`, so its users sign in on
the product's brand.

## What moves to the domain

With **Public domain** set (console: Workspace settings → General; API: `PATCH /workspace` with
`publicBaseUrl`), everything a workspace's users and relying parties see is built on it. Paths keep
their `/t/{slug}` prefix:

| | Before | After |
|---|---|---|
| Issuer (`iss`) and discovery | `https://sso.example.com/t/acme` | `https://auth.acme.com/t/acme` |
| Sign-in, registration, MFA, account pages | `https://sso.example.com/t/acme/...` | `https://auth.acme.com/t/acme/...` |
| Emailed links (verify, reset, invite, magic link) | instance address | the domain |
| Social and OIDC broker callback | `https://sso.example.com/t/acme/auth/social/{p}/callback` | `https://auth.acme.com/t/acme/auth/social/{p}/callback` |

The admin console stays on the instance address. The admin API, SCIM and the token endpoints answer
on both addresses.

The domain is an origin only: `https://` plus host and optional port, with no path. Plain `http` is
accepted only for `localhost` during development. An explicit **Issuer URL** still overrides the
issuer, as before.

## Before you set it

1. **Route the domain to the instance.** Point DNS at your proxy and have the proxy forward
   `auth.acme.com` to Kotauth, with TLS terminated at the proxy as for the instance address.
2. **Register the new callback at every identity provider** the workspace signs in with. Its
   settings page in the console shows the callback on the new domain.
3. **Tell your relying parties the new issuer.** Tokens issued after the change carry
   `iss: https://auth.acme.com/t/acme`, and validators that pin the old issuer refuse them. Users
   stay signed in; their next refresh gets a token with the new issuer. Redirect URIs registered
   on your applications are not changed for you.

The workspace's built-in account portal client is re-registered on the new domain automatically.

## Moving a workspace to another instance

The domain is part of the workspace backup, and import keeps it, so a workspace restored on another
instance comes back on the same domain. Repoint DNS at the new instance.
