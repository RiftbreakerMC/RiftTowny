-- Configured flag overrides: the layers between an administrator's restrictions and the built-in
-- defaults.
--
-- One row is one opinion: this flag, for this relationship, at this scope, is allowed or denied.
-- Partial on purpose - an absent row means "no opinion", which is what lets a claim override one
-- flag without restating every other, and what makes the layered resolution work at all.
--
-- The target is a single canonical string rather than a nullable column per scope. A composite
-- unique key over nullable columns behaves differently on the two dialects (NULLs never collide),
-- so the natural constraint "one opinion per scope, target, flag and relationship" would silently
-- not be a constraint at all. Encoding the target keeps one index and one meaning:
--
--   ADMIN         *
--   WORLD         <world uuid>
--   CLAIM         <world uuid>:<chunk x>:<chunk z>
--   ORGANISATION  <town or nation uuid>
--
-- Deliberately not a foreign key to rt_town or rt_claim. The column holds four different kinds of
-- identifier, so it cannot reference one table; orphaned rows are swept when their owner is
-- deleted, and an orphan that survives resolves against a target nothing ever asks about.

CREATE TABLE rt_flag_override (
    scope        VARCHAR(16) NOT NULL,
    target       VARCHAR(96) NOT NULL,
    flag         VARCHAR(32) NOT NULL,
    relationship VARCHAR(16) NOT NULL,
    allowed      INTEGER     NOT NULL,
    set_by       CHAR(36),
    set_at       BIGINT      NOT NULL,
    PRIMARY KEY (scope, target, flag, relationship)
);

-- Resolution reads every opinion for one target at a time, and the primary key already leads with
-- (scope, target). This second index serves the other direction: finding every override a town or
-- world holds, for a listing and for the sweep when one is deleted.
CREATE INDEX idx_rt_flag_override_target ON rt_flag_override (target);
