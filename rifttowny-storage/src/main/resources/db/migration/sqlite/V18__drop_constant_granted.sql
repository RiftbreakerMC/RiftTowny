-- Drops rt_role_permission.granted, which has only ever held 1.
--
-- Inserted as a literal (ConnectionRoleStore: "VALUES (?, ?, 1)") and read only as "granted = 1".
-- The column was there for an explicit deny - a role that takes a permission away rather than
-- adding one - and no code path can produce it: revoking deletes the row and a save replaces every
-- row for the book, so granted = 0 is a state nothing can write and nothing would keep.
--
-- Deleted rather than kept against a future need. An explicit deny would be a real feature: roles
-- are additive today, so a role cannot subtract from what a member's baseline already grants. But
-- nothing has asked for it, no plan entry names it, and inventing the semantics under cover of a
-- cleanup is how a column ends up meaning whatever the first caller assumed. If it is designed
-- later it comes back as a migration with a purpose, alongside the code that reads it.

ALTER TABLE rt_role_permission DROP COLUMN granted;
