-- Which system role a row is, not merely whether it is one.
--
-- V1 carried a boolean system_role, which cannot distinguish the leader from the member or visitor
-- role. All three are undeletable, but only the leader's permission set is fixed and only the
-- member role is the baseline residency grants - the loader has to tell them apart.
--
-- The boolean is dropped rather than kept alongside. Two columns describing the same fact drift,
-- and the derived one is always the one that goes stale.

ALTER TABLE rt_role DROP COLUMN system_role;
ALTER TABLE rt_role ADD COLUMN system_type VARCHAR(16) NULL;
