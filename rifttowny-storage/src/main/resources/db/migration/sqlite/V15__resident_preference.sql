-- What a player has chosen for themselves.
--
-- A row exists only for somebody who has actually chosen something. That is the whole design of the
-- table rather than an optimisation: absence means "never chose" and is different from a stored
-- "off", so an operator who later changes a server-wide default moves everybody who never expressed
-- a preference and leaves everybody who did exactly where they put themselves.
--
-- V12 argues the same case for a town's map colour - "NULL means 'no colour chosen', which is a
-- different thing from grey" - and this takes it one step further by making the absent state the
-- absent row, so no read ever has to collapse a third value into a boolean.
--
-- One typed column per preference rather than a key-value pair. There is exactly one preference
-- today and there will not be many: a column is read in the same row as the rest, cannot hold a
-- value no enum knows, and costs a migration only when a genuinely new thing becomes settable -
-- which, on the evidence of this slice, is rare.

CREATE TABLE rt_resident_preference (
    resident_id      CHAR(36) NOT NULL,
    territory_notice VARCHAR(16),
    updated_at       BIGINT   NOT NULL,

    PRIMARY KEY (resident_id)
);
