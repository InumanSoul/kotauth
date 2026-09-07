# ADR-19 — `MergedScimResource`: a PATCH body can only reach persistence through the merge engine

**Status:** Accepted

## Context

The SCIM mappers translate a `ScimResource` — the protocol's neutral attribute tree — into domain objects.
An attribute absent from that tree means **"clear this field"**, which is correct for `PUT`: RFC 7644 §3.5.1
defines `PUT` as a full replace, so the request body already is the complete desired state.

A `PATCH` body is the opposite. RFC 7644 §3.5.2 operations describe a *delta*; an attribute nobody mentioned
means "leave it alone". Handing a raw `PATCH` body to the same mapper reads every unmentioned attribute as a
clear — a two-line request that only touches `displayName` would blank the group's `externalId` and empty its
membership, and answer `200`.

Both cases are the same Kotlin type. Nothing in the type system distinguished "this tree is a complete desired
state" from "this tree is a delta", and the only thing standing between the second case and silent data loss was
each route remembering to call the patch engine first.

## Decision

`toDomain` on both mappers takes `MergedScimResource`, not `ScimResource`. It is a `@JvmInline value class`
with a **private constructor** and exactly two ways to obtain one:

- `MergedScimResource.fromFullReplace(resource)` — for `PUT` and `POST`, where the body genuinely is the end
  state.
- `ScimPatchEngine.applyMerged(current, ops)` — the only route to a merge-flavored instance. It applies the
  operations to `toResource(existing)` and wraps the result. Its wrapping factory is `internal` and documented
  as having exactly one caller.

There is deliberately **no** public factory that builds one from an arbitrary `ScimResource`.

## Rationale

A plain public constructor would compile identically for both cases. It catches "forgot to wrap" — a mistake the
compiler would have caught anyway — and misses "wrapped the wrong thing", which is the actual catastrophe. Two
named factories force each call site to state which case it is in, and a `PATCH` route physically cannot
construct the type without calling the patch engine.

The cost is one wrapper type and two factory names. The alternative is a convention ("always merge before
mapping") enforced by review, on a code path where the failure mode is a `200 OK` that destroyed data — the
category of defect this phase has repeatedly had to fix after the fact.

`value class` keeps it free at runtime: it erases to the underlying `ScimResource`.

## Consequences

- A new SCIM route cannot map a `PATCH` body without going through `ScimPatchEngine.applyMerged`. This is the
  point.
- The merge baseline is `toResource(existing)`, so a `PATCH` inherits every check the read path applies, and the
  attribute-shape validation at `toDomain`'s entry point covers `PUT`, `POST` and the merged `PATCH` result
  alike — one rule, three verbs.
- `toResource` grew a nullable `location` parameter for this: the merge baseline is not a client-facing
  response and must not carry a `meta.location`.
- Any future resource type gets the same contract by taking `MergedScimResource` in its own `toDomain`.

## Related

- ADR-01 (hexagonal architecture) — the type lives in `domain/scim` and imports no framework.
- ADR-06 (`AdminResult<T>`) — mapper failures are returned as `Result.failure(ScimFailure)`, never thrown.
