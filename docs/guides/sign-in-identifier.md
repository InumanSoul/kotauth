# Sign-in identifier modes

Kotauth v1.24.0 adds a per-workspace choice of what a user types into the
identifier field of the hosted login form: username, email address, or
either.

## The three modes

The **Sign-In Identifier** section of the workspace Security Policy page
(`/admin/workspaces/<slug>/settings/security`) offers three options:

- **Username only** — users sign in with their username. This is the
  default, and preserves the behaviour every workspace had before v1.24.0.
- **Email only** — users sign in with their email address.
- **Username or email** — users may enter either. If a submitted value
  matches one account's username and a *different* account's email, sign-in
  is refused rather than guessing which account was meant. The refusal is
  the same generic failure as any other bad credential, so it is not
  externally distinguishable from a wrong password.

**Existing workspaces are unaffected until an admin changes this setting.**
Migration `V65` defaults every existing tenant to `USERNAME`, so upgrading
to v1.24.0 does not change what anyone can sign in with.

The hosted login form adapts its identifier label, input type, and
`autocomplete` hint to the workspace's mode — for example, `Email only`
renders an `email`-type field with `autocomplete="email"`, while
`Username only` keeps a plain text field so a value that happens to look
like an email is not blocked by native browser validation.

## Email sign-in does not require a verified address

This is a deliberate decision, not an oversight. Username sign-in has never
checked whether a user's email is verified, and gating email sign-in on
verification would lock out invite- and SCIM-provisioned users who have
never completed the verification step — exactly the accounts this feature
exists to serve. Do not add an `emailVerified` check to the email or either
lookup path; it would be a regression, not a fix.

## Preventing an unresolvable pair

Because usernames and email addresses are separately unique namespaces, the
database alone would allow a pair that `EITHER` mode cannot resolve: user A's
username equal to user B's email (or vice versa). Kotauth rejects that pair
at write time instead — across admin user creation and update, SCIM, and
self-registration — regardless of which mode the workspace currently uses,
so switching to `EITHER` later never surfaces a latent collision.

Social login, JIT provisioning, and backup import do not run this check —
they provision users outside the admin/SCIM/self-registration paths and can
still create a colliding pair. An administrator can resolve one after the
fact by renaming the username, which is validated the same way.

A user whose username **is their own** email address is unaffected and
remains fully supported; that is the shape most integrators actually want.

## Auto-generated and admin-editable usernames

When a user is provisioned without a username — an admin-created invite or
API call that supplies only a name and email — Kotauth generates one: a
readable stem from the given name (falling back to the email's local part),
followed by a short random suffix, checked against both the username and
email namespaces so generation can never manufacture the collision above.
This applies to the admin API and admin UI only. SCIM's `userName` is
REQUIRED per RFC 7643, so a SCIM push omitting it is rejected rather than
generated.

Usernames are no longer immutable. An administrator can rename a user's
username from the admin UI or the admin API, subject to the same collision
check. Renaming validates only the new value — a stored username that
predates this validation (or predates any format checking at all, as with
self-registration and backup-imported accounts) does not block unrelated
edits to the same user, such as updating their email or name.

## Username normalization

Every path that can create or rename a username — admin create, admin
rename, self-registration, social login, JIT provisioning, email-OTP
sign-up, and backup import — now normalizes it the same way before writing:
trimmed of surrounding whitespace, lowercased, then required to match
`[a-zA-Z0-9._@+-]+`. A value that fails to normalize into something valid
is rejected rather than stored as-is. Self-registration previously ran no
username validation at all; it is now covered by the same rule as every
other path.

One consequence: sign-in matches usernames **case-insensitively**. Storage
is always lowercase, and the identifier submitted at sign-in is lowercased
before lookup, so a user who originally signed up or was created as `Dave`
still signs in as `Dave`, `DAVE`, or `dave` — their stored username is
`dave`.

## Upgrading to v1.24.0

Migration `V66` normalizes every username already in the database to the
rule above, then adds a unique index on `(tenant_id, lower(username))` so
two usernames differing only by case (or by punctuation collapsed to `.`)
can no longer coexist. This has two consequences an operator must plan for
before upgrading:

- **Existing sign-in identifiers can change.** A user stored as `"John
  Doe"` becomes `john.doe`; a user stored as `Dave` becomes `dave`. Anyone
  whose stored username was not already trimmed, lowercase, and
  pattern-valid needs to be told their new sign-in identifier before or
  right after the upgrade.
- **The migration aborts the upgrade if it would have to merge two
  identities.** If two usernames in the same tenant would normalize to the
  same value — e.g. `Dave` and `dave`, or `john.doe` and `John_Doe` — `V66`
  raises an exception and the migration (and therefore the upgrade) does
  not complete. This is deliberate: silently merging or dropping one of the
  two accounts would be worse than a blocked upgrade. The operator must
  rename one of the colliding accounts by hand and re-run the migration.
- **The migration aborts the upgrade if a username would normalize to
  nothing at all.** A username made entirely of characters outside
  `[a-z0-9._@+-]` — most notably a non-Latin username such as `Иван` or
  `用户` — collapses to an empty string under `V66`'s own rewrite rule.
  `users.username` is `NOT NULL` with no format `CHECK` before this
  migration, so an empty string would otherwise commit silently, and that
  user could never sign in by username again with no error and no log line
  anywhere. `V66` refuses to let that happen: it raises an exception naming
  the tenant and the row's `id` (never the raw username, the same
  restraint the collision check above already uses) and the migration does
  not complete. **The operator must rename that user by hand, on the
  pre-upgrade version, to something matching `[a-z0-9._@+-]+` before
  re-running the migration.** This is a real possibility for any
  self-hosted install used outside the anglosphere — `AuthService.register`
  historically ran no username validation at all, so such rows can exist in
  the wild.

Run these queries against the target database **before** upgrading.

Every tenant/username group that would collide once normalized. This
mirrors `V66`'s own normalization exactly — lowercase, trim, collapse
forbidden-character runs to `.`, then strip leading/trailing `.`/`_`/`-`:

```sql
SELECT tenant_id,
       regexp_replace(
         regexp_replace(
           regexp_replace(lower(btrim(username)), '[^a-z0-9._@+-]+', '.', 'g'),
           '^[._-]+', ''
         ),
         '[._-]+$', ''
       ) AS normalized,
       count(*)
FROM users
GROUP BY 1, 2
HAVING count(*) > 1;
```

Any row this returns is an upgrade blocker: rename one of the colliding
usernames (through the admin UI or API, on the pre-upgrade version) until
the query returns nothing, then upgrade.

Every row that would normalize to an empty or otherwise still-invalid
value — the companion query for the non-Latin-username case above:

```sql
SELECT id, tenant_id, username
FROM users
WHERE regexp_replace(
        regexp_replace(
          regexp_replace(lower(btrim(username)), '[^a-z0-9._@+-]+', '.', 'g'),
          '^[._-]+', ''
        ),
        '[._-]+$', ''
      ) !~ '^[a-z0-9._@+-]+$';
```

Any row this returns is also an upgrade blocker: rename the user (through
the admin UI or API, on the pre-upgrade version) to a username matching
`[a-z0-9._@+-]+` until the query returns nothing, then upgrade.

## Sign-in failure message

The generic sign-in failure changed from "Invalid username or password." to
**"Invalid sign-in details."**, since the old wording named "username",
which was misleading for a workspace in `EMAIL` mode.

## A note on whitespace

The identifier submitted at sign-in is now trimmed of surrounding whitespace
before lookup; previously it was passed through raw. In `USERNAME` mode this
means a username pasted with a trailing space, which previously failed, now
signs in. This is safe because every write path — `createUser`, `register`,
social login, JIT provisioning, email-OTP sign-up, and backup import —
normalizes (trims and lowercases) before storing, so a stored username can
no longer carry leading or trailing whitespace at all; trimming at lookup
can only ever resolve the same account the untrimmed value would have,
never a different one. Backup import used to be the one exception, writing
a restored username verbatim; it now normalizes and rejects the record
instead if the result isn't valid (see Upgrading, below, for the equivalent
cleanup `V66` performs on rows already in the database).

## A note on rolling deploys

During a rolling deploy, old and new replicas serve traffic side by side.
Switching a workspace's mode away from `USERNAME` takes effect immediately
in the database, but a request that happens to land on an old replica still
resolves by username only, regardless of the new setting, until that
replica is replaced.

`V66` itself has the same exposure. It runs once, at the start of the
deploy, and rewrites every stored username immediately — but an old
replica still running the previous release's code may do a case-exact
username comparison somewhere in its own request handling (rather than
going through a case-insensitive lookup). For the window between `V66`
running and the last old replica draining, such a replica will fail to
match a user whose stored username `V66` just rewrote — e.g. a user stored
as `Dave` is now `dave` in the database, and an old replica comparing the
submitted value byte-for-byte against the row it fetches will not find it.
This resolves itself as soon as the rolling deploy finishes and only new
replicas remain; there is no user-visible action to take beyond finishing
the deploy promptly.
