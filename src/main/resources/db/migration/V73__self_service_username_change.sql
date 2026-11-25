-- V73: Users may change their own username from the account portal, per workspace.
--
-- Off by default: usernames stay administrator-controlled until an operator turns it on.
-- username_self_changed_at holds the user's last rename of their own account and backs the
-- cooldown between two of them. Administrator renames leave it untouched.

ALTER TABLE tenant_security_config ADD COLUMN username_change_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE users ADD COLUMN username_self_changed_at TIMESTAMPTZ NULL;
