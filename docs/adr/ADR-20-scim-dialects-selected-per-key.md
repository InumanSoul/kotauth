# ADR-20 — A SCIM dialect is selected per API key, never sniffed from the request

**Status:** Accepted

## Context

RFC 7644 defines one wire format. Enterprise provisioning clients document deviations from it, and the
deviations are not cosmetic: one sends `active` as the strings `"True"` and `"False"`, another attaches an
advisory `display` name beside every group member id it pushes. (A third documented difference, capitalised
`op` verbs, needs no dialect at all: the canonical parser has always matched the verb case-insensitively.)

KotAuth's SCIM parser is strict on purpose. A string where the schema says boolean is a `400 invalidValue`
naming the attribute, never a coerced value — because a deprovision that quietly does nothing is a security
defect while a `400` is an operator's cue to fix the mapping. Strict server plus deviating client means
provisioning works until the first deactivation and then fails, which is the worst possible place for it to
fail.

So something has to normalise the wire before the parser sees it. The two open questions are where that
normalisation lives, and how the server decides which normalisation a given request needs.

## Decision

**A `ScimDialect` reshapes the request body in the web adapter, and which one applies is a property of the API
key that authenticated the request.**

- `ScimDialect` lives in `adapter/web/scim`. It exposes exactly two operations — normalise a `PatchOp` body,
  normalise a resource body — and both hand their result to `RfcDialect` for the single canonical parse.
  Nothing else in the codebase branches on a dialect.
- `RfcDialect` (id `rfc`) is a pass-through straight to the canonical parser. Two vendor dialects are
  registered beside it in one map, which is also what the admin selector and the per-provider setup notes are
  rendered from.
- The id is persisted on the key: `api_keys.scim_dialect VARCHAR(16) NOT NULL DEFAULT 'rfc'` (V62). An
  operator picks it when creating a provisioning key and can correct it afterwards from the workspace
  provisioning page. A key provisioned through `KAUTH_BOOTSTRAP_API_KEYS` carries it as the entry's
  optional `scimDialect` field, validated against the registry at startup and re-asserted on every boot.
- `call.scimDialect()` resolves the authenticated key's id through the registry. **No request header is
  consulted, ever.**
- An id the running build does not recognise resolves to `RfcDialect` rather than failing the request.
- A dialect may only reshape an inbound body. It never touches a response, a repository, or a service, and
  `domain/scim` has no knowledge that dialects exist.

## Rationale

### Why explicit selection rather than detection

Detection would key off `User-Agent`, which is the only thing on a SCIM request that hints at the client.
That string is not a contract — vendors change it between releases without notice, and nothing obliges a
customer's outbound proxy to preserve it.

The cost of getting it wrong is what settles the argument. A misread does not produce an error; it produces a
*different interpretation of the same payload*, applied silently, under a `200 OK` that tells the connector
everything worked. That is the same defect class this phase has repeatedly had to fix after the fact — the
destructive success — and adding a fresh source of it to buy a small convenience is not a trade worth making.

The convenience is small. Detection saves the operator one dropdown, once, at the moment they are already
inside the provider's console pasting a base URL and a token. They know which provider it is; they are
configuring it. Asking is cheaper than guessing, and the answer is then a stored fact rather than a
per-request inference.

### Why the key rather than the workspace

The key *is* the connection. A workspace may run more than one provisioning client — a directory push and an
HR feed, or a spec-compliant client alongside a quirky one during a migration — and per-key selection lets
them coexist without either being reinterpreted. It also means the dialect arrives with the credential the
request already had to present: there is no second lookup and no window where the two disagree.

### Why the boundary is the adapter

`domain/scim` is a strict RFC 7643/7644 implementation and stays one. A dialect normalises *shape*; what an
operation *means* remains with the patch engine, which is deliberately vendor-blind. Keeping the two apart is
what stops "this client is odd" from becoming a branch inside the merge logic, where the blast radius is every
client.

A payload a dialect cannot map to a canonical shape fails rather than being guessed at. Inventing a member id
from a bare string is not normalising — it is deciding what the caller meant, which is exactly the move this
ADR exists to refuse.

What a dialect buys is therefore narrower than it looks, and worth stating plainly: it changes what the strict
core *rejects*, never what gets stored. Dropping the advisory `display` beside a member id persists nothing
either way — no mapper reads it — but it means a connector sending that sub-attribute with the wrong JSON type
loses the sub-attribute rather than the whole member push.

### Why an unrecognised id falls back instead of failing

A key configured for a dialect a later build no longer ships must keep provisioning against the spec, not
start rejecting every payload. Falling back to `rfc` degrades to the standard; failing closed on a
configuration value degrades to an outage.

### What the fixtures are, and what they are not

Every vendor fixture in `src/test/resources/scim/fixtures/` is **hand-built from published vendor
documentation. None of it is captured traffic.** Each file carries an `_source` header saying so, stripped at
load time so it can never reach a dialect and influence normalisation.

This is recorded here because it is easy to lose. A reader finding a directory of realistic vendor payloads
will reasonably assume someone pointed a real tenant at this server, and every claim downstream of that
assumption is stronger than the evidence supports.

The claim this layer earns today is **"implements the deviations the vendors document"**. It is not
"verified against" any named provider, and nothing — the changelog, the README, the operator-facing docs, the
admin copy — may say otherwise until an end-to-end pass against real tenants replaces these fixtures with
captured traffic. That pass is what upgrades the claim; nothing else does.

## Consequences

- **Nothing here changes behaviour for an existing client.** `rfc` is the column default, V62 backfills every
  existing key to it, and it is a pure pass-through. A workspace that never touches the selector sends and
  receives exactly what it did before.
- Vendor names reach operators as copy and nothing else. Labels and setup notes hang off the dialect object
  and are worded in `EnglishStrings`, so the admin package renders a selector and a notes panel without naming
  a provider. The only vendor tokens in control flow are the three registry ids, in one package.
- Adding a provider is one object added to the registry. The selector, the notes panel, and the persisted-id
  lookup all follow from it — there is no second list to keep in step.
- The dialect is the one field of an existing key an operator can correct in place. Choosing wrong surfaces
  later as rejected payloads, and forcing a new key would mean reconfiguring the connector for a typo. Key
  material, scopes, expiry and the enabled flag each keep their own decision. A key provisioned through
  `KAUTH_BOOTSTRAP_API_KEYS` refuses the edit *because the environment has its own field for it*: the entry's
  `scimDialect`, which every restart re-applies. The refusal points the operator at that field, so declining
  the edit closes a door that has another one beside it rather than the only one there is.
- The provisioning page does not claim a connection is healthy. Nothing in the audit log records an individual
  SCIM request or the key that made it, so any status shown would be inferred rather than observed, and an
  operator trusting a wrong "connected" is worse off than one told plainly that the answer is not available
  yet.
- A record an identity provider owns is marked as such wherever `externalId` is set. KotAuth stores that a
  record is externally provisioned, never which provider provisioned it, so that badge names no vendor either.

## Related

- ADR-01 (hexagonal architecture) — dialects are an adapter concern; `domain/scim` imports no vendor knowledge.
- ADR-02 (Flyway migrations) — V62 is additive and immutable once released.
- ADR-19 (`MergedScimResource`) — a dialect normalises before the merge engine runs, so a `PATCH` still reaches
  persistence only through `ScimPatchEngine.applyMerged`.
