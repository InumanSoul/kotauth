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

## Which address answers what

Once a workspace has a domain, Kotauth keeps its pages there:

- **Its pages on the instance address are redirected** (308, path and query kept) to the domain:
  sign-in, authorize, registration, MFA, the account portal, emailed links. An old bookmark or a
  relying party that still sends users to the instance address keeps working.
- **Its domain serves only that workspace.** Another workspace's pages, and the admin console,
  answer 404 there, so no other brand appears under the product's domain.
- **Admins stay on the instance address.** A browser signed in to the console is not redirected,
  so impersonating a user, and any workspace link opened from the console, works there.
- **Servers can use either address.** The admin API, SCIM, discovery, JWKS and the token,
  userinfo, introspection and revocation endpoints answer on both, so backends can migrate on
  their own schedule.

Hosts are compared by name only, not scheme or port. Behind a proxy, make sure it forwards the
`Host` the user typed (most do by default), or set `X-Forwarded-Host` with `KAUTH_TRUSTED_PROXY`.
A domain set or cleared in the console or the API applies at once on the replica that saved it, and within 30 seconds on the others.

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

## Passkeys

Passkeys stay bound to the instance host: its name is the relying party ID, so passkeys users
already registered keep working after the switch, and nobody has to register again. A browser on
`auth.acme.com` asks for passkeys of `sso.example.com`, and checks that it may by fetching
`https://sso.example.com/.well-known/webauthn`, which Kotauth serves with every workspace domain
listed (WebAuthn Related Origin Requests).

- **The instance address must stay reachable over https** at the relying party ID, even if users
  never visit it.
- **Browser support:** Chrome and Edge 128+, Safari 18+. Check Firefox support for your users'
  versions before relying on it. A browser without support refuses passkeys on the workspace
  domain, and users sign in another way.
- **At most five distinct site names.** Browsers honour five registrable-domain labels in that
  list (`acme` in `auth.acme.com` and `acme.de` count once). Beyond five, passkeys stop working on
  the extra domains.

## Moving a workspace to another instance

The domain is part of the workspace backup, and import keeps it, so a workspace restored on another
instance comes back on the same domain. Repoint DNS at the new instance.
