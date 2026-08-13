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
-- The unique constraint on former_town_id goes with it, and that is the point of rebuilding the
-- table rather than adding three columns. Restoring a town under its own id means a town can fall
-- twice, and two falls are two ruins - the constraint would have made the second disband fail with a
-- constraint violation rather than a sentence.
--
-- A separate migration rather than an edit to V6, which has already shipped. Flyway validates
-- checksums, so changing an applied migration breaks every database that ran it.

CREATE TABLE rt_ruin_v7 (
    ruin_id          CHAR(36)    NOT NULL,
    former_town_id   CHAR(36)    NOT NULL,
    name             VARCHAR(64) NOT NULL,
    founder_id       CHAR(36),
    ruined_at        BIGINT      NOT NULL,
    reclaimable_from BIGINT      NOT NULL,
    expires_at       BIGINT      NOT NULL,
    reclaimed_at     BIGINT,
    reclaimed_by     CHAR(36),
    reclaimed_as     CHAR(36),
    homeblock_world  CHAR(36),
    homeblock_x      INTEGER,
    homeblock_z      INTEGER,
    PRIMARY KEY (ruin_id)
);

INSERT INTO rt_ruin_v7 (
    ruin_id, former_town_id, name, founder_id, ruined_at, reclaimable_from, expires_at,
    reclaimed_at, reclaimed_by, reclaimed_as, homeblock_world, homeblock_x, homeblock_z)
SELECT
    ruin_id, former_town_id, name, founder_id, ruined_at,
    -- Existing ruins open immediately. They were made under a version that had no delay, and
    -- retroactively closing them would be a rule nobody was told about.
    ruined_at,
    expires_at, reclaimed_at, reclaimed_by, reclaimed_as, NULL, NULL, NULL
FROM rt_ruin;

-- The claims are rebuilt too, because their foreign key points at the table being replaced. Copied
-- first, then both old tables dropped child-before-parent so the key holds throughout.
CREATE TABLE rt_ruin_claim_v7 (
    world_id CHAR(36) NOT NULL,
    chunk_x  INTEGER  NOT NULL,
    chunk_z  INTEGER  NOT NULL,
    ruin_id  CHAR(36) NOT NULL,
    PRIMARY KEY (world_id, chunk_x, chunk_z),
    CONSTRAINT fk_rt_ruin_claim_ruin FOREIGN KEY (ruin_id) REFERENCES rt_ruin_v7 (ruin_id) ON DELETE CASCADE
);

INSERT INTO rt_ruin_claim_v7 (world_id, chunk_x, chunk_z, ruin_id)
SELECT world_id, chunk_x, chunk_z, ruin_id FROM rt_ruin_claim;

DROP TABLE rt_ruin_claim;
DROP TABLE rt_ruin;

-- Renaming the parent rewrites the child's foreign key to follow it, so the order here matters.
ALTER TABLE rt_ruin_v7 RENAME TO rt_ruin;
ALTER TABLE rt_ruin_claim_v7 RENAME TO rt_ruin_claim;

CREATE INDEX idx_rt_ruin_expiry ON rt_ruin (expires_at);
CREATE INDEX idx_rt_ruin_former_town ON rt_ruin (former_town_id);
CREATE INDEX idx_rt_ruin_claim_ruin ON rt_ruin_claim (ruin_id);
