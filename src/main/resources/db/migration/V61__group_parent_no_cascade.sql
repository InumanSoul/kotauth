-- -----------------------------------------------------------------------------
-- groups.parent_group_id — a parent delete must never destroy its descendants
--
-- V12 declared this self-reference ON DELETE CASCADE, so deleting a parent group
-- also removed every descendant group and, through their own cascades, those
-- subgroups' memberships (user_groups) and role grants (group_roles). Deleting a
-- group that still has children is now refused by the database. The service layer
-- reports the same rule as a conflict that names the subgroups, so operators get an
-- explanation rather than a constraint violation; this constraint is the backstop.
--
-- NO ACTION rather than RESTRICT: verified on PostgreSQL 15 that both refuse the
-- direct parent delete and both still allow a tenant delete (groups.tenant_id
-- ON DELETE CASCADE removes the whole tree in one statement, and the referential
-- check ignores rows the same statement removed). Only NO ACTION can ever be
-- deferred, so it leaves room for a future reparent-then-delete batch in one
-- transaction; RESTRICT is checked immediately even when declared DEFERRABLE.
-- -----------------------------------------------------------------------------
ALTER TABLE groups DROP CONSTRAINT groups_parent_group_id_fkey;

ALTER TABLE groups
    ADD CONSTRAINT groups_parent_group_id_fkey
        FOREIGN KEY (parent_group_id) REFERENCES groups (id) ON DELETE NO ACTION;
