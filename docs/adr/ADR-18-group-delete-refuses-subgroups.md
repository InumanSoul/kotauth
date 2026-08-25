# ADR-18 — Deleting a group with subgroups is refused, not cascaded

**Status:** Accepted

## Context

`groups.parent_group_id` is a self-reference. Migration V12 declared it `ON DELETE CASCADE`, so deleting a
parent group deleted every descendant group with it, and — through their own cascades — those subgroups'
memberships (`user_groups`) and role grants (`group_roles`).

Nothing in the admin UI or the REST API said so. An operator deleting one group could destroy an arbitrarily
deep subtree, along with every permission grant hanging off it, from a single confirmation dialog. There is no
undo: the rows are gone, and nothing in the audit log is sufficient to rebuild the grants.

Group provisioning over SCIM makes this sharper rather than softer. `DELETE /Groups/{id}` is a routine
operation for a directory connector, issued without a human in the loop, and a connector that deletes a
container group would silently take out every group beneath it.

## Decision

A group that still has at least one subgroup cannot be deleted. The rule is enforced twice:

- **In `RoleGroupService.deleteGroup`** — the single path the admin UI, the REST API and the SCIM endpoint all
  go through. It returns a conflict naming the blocking subgroups (capped at five, then "and N more"), so the
  operator is told what to resolve rather than being told "no".
- **In the database (V61)** — `groups_parent_group_id_fkey` is redeclared `ON DELETE NO ACTION`. This is the
  backstop for any future write path that forgets the service rule.

Resolving the block is the operator's decision: reparent the subgroups, or delete them first.

## Rationale

### Why refusing beats reparenting

`ON DELETE SET NULL` is the obvious-looking alternative — orphaned subgroups become top-level groups and no
data is lost. It does not work here, because group names are unique per `(tenant, parent)`, not per tenant.

Given a tenant with two top-level groups both named `Contractors`, each with a subgroup named `EU`, deleting
both parents under `SET NULL` promotes two groups named `EU` to the top level, where the uniqueness constraint
now applies to them — one of the two writes fails, mid-delete. Even where the constraint happens not to fire,
the result is silent: subgroups appear at the top of the tree having never been placed there by anyone, and
whatever the hierarchy meant (a department, a customer, a region) is gone while the rows survive.

Refusing is the only option that neither destroys data nor invents a structure the operator did not ask for.

### Why `NO ACTION` rather than `RESTRICT`

Both refuse the direct parent delete. Both still allow a tenant delete, because `groups.tenant_id` cascades and
the referential check ignores rows the same statement is removing — verified on PostgreSQL 15 rather than
assumed.

The difference is deferrability: only `NO ACTION` can ever be declared `DEFERRABLE`. A future "reparent the
children, then delete the parent" batch wants both statements inside one transaction with the check deferred to
commit; `RESTRICT` is checked immediately even when declared `DEFERRABLE`, and would have to be dropped and
recreated to allow it. `NO ACTION` costs nothing today and leaves that door open.

### Why the service duplicates the constraint

A constraint violation tells an operator that a foreign key failed. It does not tell them which subgroups are in
the way, and a raw database error reaching an API client is not an error message. The service check exists to
produce the explanation; the constraint exists so the rule holds even if a future write path skips the service.

## Consequences

- **This is a behaviour change on a public API.** `DELETE /t/{slug}/api/v1/groups/{id}` and
  `DELETE /t/{slug}/scim/v2/Groups/{id}` now answer `409` for any group that has a subgroup, where they
  previously succeeded and cascaded. A caller that relied on the cascade must delete or reparent the children
  itself. Recorded in the changelog as a behaviour change.
- The admin UI disables the delete control while subgroups exist and renders the conflict on the group detail
  page for a stale page or a direct POST.
- Deleting a tenant still removes its whole group tree in one statement; that path is unaffected.
- V61 drops the old constraint without `IF EXISTS`. A database missing it is a database in an unexpected state,
  and failing loudly at migration time is the intended outcome.

## Related

- ADR-02 (Flyway migrations) — V61 is additive and immutable once released.
- ADR-06 (`AdminResult<T>`) — the refusal is an `AdminError.Conflict`, not an exception.
