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

## Sign-in failure message

The generic sign-in failure changed from "Invalid username or password." to
**"Invalid sign-in details."**, since the old wording named "username",
which was misleading for a workspace in `EMAIL` mode.

## A note on whitespace

The identifier submitted at sign-in is now trimmed of surrounding whitespace
before lookup; previously it was passed through raw. In `USERNAME` mode this
means a username pasted with a trailing space, which previously failed, now
signs in. This is safe for `createUser`, `register`, social login, JIT
provisioning, and email-OTP sign-up, all of which trim before storing —
trimming at lookup can only ever resolve the same account the untrimmed
value would have, never a different one. `BackupImporterService` is the one
exception: it writes a restored username verbatim, untrimmed, so a backup
imported from an older instance can still carry leading or trailing
whitespace in a stored username.

## A note on rolling deploys

During a rolling deploy, old and new replicas serve traffic side by side.
Switching a workspace's mode away from `USERNAME` takes effect immediately
in the database, but a request that happens to land on an old replica still
resolves by username only, regardless of the new setting, until that
replica is replaced.
