# Consent screen

An application can ask users to approve what it may access before it signs them in. The user
sees the application's name, the account they are signed in with, and one line per scope the
application asked for, then chooses **Allow** or **Deny**.

## Which applications ask

Each application has an **Ask users for consent** switch, under its settings in the console, or
`consentRequired` in the admin API (`POST /applications`, `PUT /applications/{appId}`).

- Applications created from 1.32.0 on ask by default. Turn it off for your own applications,
  where asking users to approve your app on your own sign-in page adds nothing.
- Applications that existed before 1.32.0 were upgraded with it off, so an upgrade never starts
  prompting the users of an application already in production.
- Kotauth's own account portal and admin console never ask.

Keep it on for anything a third party builds against your workspace: a partner integration, a
customer's own tool, an app published by someone else.

## When users see it

The first time a user signs in to an application that asks, after they have proved who they are
(password, passkey, magic link, email code, a social provider, and the second factor when one
is owed). Kotauth then remembers what they allowed, per user and application, and does not ask
again until:

- the application requests a scope the user has not allowed yet. The screen lists the full
  request, and allowing it adds the new scopes to the ones already granted;
- the application sends `prompt=consent`;
- the user or an administrator revokes the application's access.

A single sign-on session does not skip the screen: it skips the password, not the approval.

## What the application receives

| User does | Application receives |
|---|---|
| Allow | the authorization code, as without the screen |
| Deny | `error=access_denied` at its redirect URI, with its `state` |
| Nothing, and the application sent `prompt=none` | `error=consent_required` |

`prompt=consent` asks for the consent screen only. From 1.32.0 on it no longer forces the user to
sign in again; send `prompt=login` for that.

## How scopes are described

- `openid`, `profile` and `email` have built-in text: confirm who you are, see your name and
  username, see your email address.
- A scope an API declares (Applications → APIs) shows the text you give it, with the API's name
  under it. In the console, write it on the scope's line after the scope:

  ```
  orders:read See your orders
  orders:write Place and cancel orders
  ```

  In the admin API, send `scopeDescriptions` with the resource server, a map from scope to text
  of up to 200 characters.
- Any other scope is shown by name.

## Revoking access

- **Users**: account portal → Security → **App access** lists every application they allowed and
  what it can do, with **Remove access**.
- **Administrators**: the user's page in the console has an **Application access** card with
  **Revoke**; in the admin API, `GET /users/{userId}/consents` and
  `DELETE /users/{userId}/consents/{appId}`.

Revoking deletes the grant and ends that application's sessions for the user, so its refresh
tokens stop working. The user sees the consent screen at their next sign-in to it.

## Audit log

| Event | When |
|---|---|
| `CONSENT_GRANTED` | the user chose Allow; `scopes` lists what they approved |
| `CONSENT_DENIED` | the user chose Deny |
| `CONSENT_REVOKED` | the user removed an application's access |
| `ADMIN_CONSENT_REVOKED` | an administrator revoked it; `targetUser` names the user |

## Backups

Backups carry each application's switch and each API's scope descriptions. They do not carry
users' grants, just as they don't carry sessions: after a restore, users approve applications
again.
