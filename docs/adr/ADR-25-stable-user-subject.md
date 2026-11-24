# ADR-25 — A user's `sub` is stored, not derived from the row id

**Status:** Accepted
**Context:** OpenID Connect Core §2 and §5.7, issue #241

## Context

`sub` was the user's integer row id. OIDC tells relying parties to key their records on `iss` +
`sub`, and that pair broke whenever a workspace moved: the backup format exports no database ids,
and import assigns new ones, so every user came back with a new `sub`. Row ids are also
sequential. A `sub` revealed roughly how many users an instance had, and the next user's `sub`
could be guessed from one's own.

Keycloak issues a UUID per user. Auth0 issues `provider|id`, where the id is opaque. Neither ties
`sub` to a row of one database.

## Decision

**`users.subject` holds the `sub`.** It is unique per workspace, which is the scope `iss` gives it.

- **Existing users keep the subject they had.** Migration V71 backfills `subject` with the row id,
  so no issued token, stored link or integration changes on upgrade.
- **New users get a random UUID**, assigned when the user is first saved. The column default does
  the same for any insert that bypasses the application.
- **The subject never changes after that.** Updates don't write it. It isn't derived from the id
  again.
- **Backups carry it, and import writes it.** A restored workspace keeps every user's `sub`. A
  backup taken before V71 carries none, so those users get new UUIDs, and the import summary
  counts them so the operator knows which relying parties must relink.
- **The row id stays internal.** It is still the primary key, the SCIM `id` and the console's user
  URL. Only tokens and userinfo speak `sub`. Kotauth's own console and portal resolve their sign-in
  by subject, never by parsing `sub` as a number.

## Consequences

- **A behaviour change for integrators.** A relying party that stores `sub` in an integer column
  breaks on the first user created after upgrading. That is the cost of making `sub` portable and
  unguessable, and it's called out in the release notes.
- **Impersonation** puts the acting admin's subject in `act.sub` (ADR-14).
- **Import refuses** a backup in which two records share a subject, or a subject is blank or over
  255 characters.
