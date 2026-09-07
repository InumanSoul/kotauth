# ADR-22 — Loopback redirect URIs match on any port, for public clients only

**Status:** Accepted — v1.24.1
**Context:** RFC 8252 §7.3, §8.3 · RFC 6749 §3.1.2.3

## Context

A native application has no fixed redirect target. The established pattern is to bind an
ephemeral port on the loopback interface at sign-in time and use `http://127.0.0.1:<port>/cb`,
where the port is whatever the operating system hands out that second and differs on every
launch.

Kotauth compared the requested `redirect_uri` against the client's registered URIs with exact
string equality, so an ephemeral port never matched. RFC 8252 §7.3 addresses this directly:

> The authorization server MUST allow any port to be specified at the time of the request for
> loopback IP redirect URIs, to accommodate clients that obtain an available ephemeral port
> from the operating system at the time of the request.

This surfaced from a real integration — a Compose Desktop point-of-sale terminal — which
worked around it by registering three fixed loopback ports and binding the first free one.
That works until all three are taken, and the number three is arbitrary.

## Decision

Loopback redirect URIs match on any port. The exception is deliberately narrow, and every
boundary below is enforced by a test in `RedirectUriMatcherTest`.

**Scope of the relaxation**

- `http` scheme only, on the literal loopback addresses `127.0.0.1` and `[::1]`.
- Only the port is ignored. Scheme, host, path, query and fragment must all still match.
- A URI carrying userinfo never matches, rather than the matcher deciding what
  `http://evil@127.0.0.1/cb` ought to mean next to a registration that has none.
- Applied only when the client's `accessType` is `PUBLIC`.

**`localhost` is excluded.** It resolves through the name service and can be pointed elsewhere
by a hosts-file entry or a DNS answer, so it is not equivalent to the literal addresses.
RFC 8252 §8.3 recommends the literals for exactly this reason.

**Restricted to public clients.** RFC 8252 concerns native apps, which are public clients by
definition. A confidential client has no legitimate reason to use a loopback callback, so
extending the relaxation there would widen the matching surface with no use case to justify
it. Public clients are also the ones for which Kotauth already forces PKCE with `S256`
(`OAuthService.issueAuthorizationCode`), which is the mitigation the RFC relies on.

**Two call sites change; a third does not.**

Changed — both compare a request against the client's *registered* URIs:

- `OAuthService.validateRedirectUri` — the open-redirect guard on the `prompt=none` error path.
- `OAuthService.issueAuthorizationCode` — the authorize path.

Unchanged, and deliberately so:

- `OAuthService.exchangeAuthorizationCode` compares the token request's `redirect_uri` against
  the value stored **on the authorization code**. That binds a code to the exact callback it
  was issued for. Relaxing it would let a code issued for one loopback port be redeemed while
  claiming another, which is the local interception PKCE exists to blunt. It stays exact, and
  a test asserts that a differing port at the token endpoint still fails.

## Consequences

A native client can bind whatever port the OS gives it and register a single loopback URI. The
port component of a registered loopback URI becomes advisory for public clients — registering
`http://127.0.0.1/cb` with no port is the clearest form.

Nothing changes for any deployment without a public client holding an `http` loopback
registration. Exact matches continue to match, and no relaxation reaches `https`, any named
host, or any confidential client.

The residual risk is the one the RFC accepts: another process on the same machine can bind the
port and receive the authorization code. PKCE with `S256` is what makes that code unusable, and
Kotauth requires it for every public client.

## Alternatives considered

**Leave it, and let integrators register fixed ports.** What the reporting integration did. It
fails when the chosen ports are already in use, and the number to register is a guess.

**Allow any port for any host.** Removes the reason redirect URIs are registered at all.

**Include `localhost`.** Convenient, and specifically warned against by RFC 8252 §8.3 — the
name can be redirected, the literals cannot.

**Allow it for confidential clients too.** No use case, so purely additional surface.
