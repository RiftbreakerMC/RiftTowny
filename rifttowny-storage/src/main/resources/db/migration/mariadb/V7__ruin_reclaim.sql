-- What a ruin has to remember to restore a town rather than replace it.
--
-- Reclaiming brings the fallen town back under its own name and its own id, so the ruin carries the
-- two things a rebuilt town cannot work out for itself: when it becomes reclaimable, and which chunk
-- was its home.
--
-- reclaimable_from is stored rather than derived from ruined_at plus the configured delay. The delay
-- is configuration and configuration changes; a player told a ruin opens at a particular time should
-- not find that moved because an operator edited a file in between.
--
-- The unique constraint on former_town_id is dropped. Restoring a town under its own id means a town
-- can fall twice, and two falls are two ruins - the constraint would have made the second disband
-- fail with a constraint violation rather than a sentence.
--
-- A separate migration rather than an edit to V6, which has already shipped. Flyway validates
-- checksums, so changing an applied migration breaks every database that ran it.

ALTER TABLE rt_ruin
    ADD COLUMN reclaimable_from BIGINT NOT NULL DEFAULT 0,
    -- The homeblock the town had when it fell. Nullable: a town can be disbanded without ever
    -- having set one, and a ruin of a town with no home has no home to restore.
    ADD COLUMN homeblock_world CHAR(36) NULL,
    ADD COLUMN homeblock_x INT NULL,
    ADD COLUMN homeblock_z INT NULL,
    DROP INDEX uq_rt_ruin_former_town,
    ADD INDEX idx_rt_ruin_former_town (former_town_id);

-- Existing ruins open immediately. They were made under a version that had no delay, and
-- retroactively closing them would be a rule nobody was told about.
UPDATE rt_ruin SET reclaimable_from = ruined_at WHERE reclaimable_from = 0;
