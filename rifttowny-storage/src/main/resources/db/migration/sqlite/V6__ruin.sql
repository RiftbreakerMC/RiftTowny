-- Ruins: what a town leaves behind.
--
-- A disbanded town's land does not become wilderness the moment its last resident goes. The
-- buildings are still standing, and turning them into unowned ground instantly means the first
-- player to walk past owns everything in them. A ruin is the interval between: the shell stays
-- protected, it can be reclaimed whole by somebody willing to take it on, and only when the window
-- closes does the land actually revert.
--
-- The ruin row outlives its claims on purpose. It is deleted by nothing: RT-MOD-REGEN needs to know
-- what to restore, and docs/war-decisions.md keys anti-recreation on "the previous organisation's
-- ruin record", which cannot be a record if it is swept the moment the land reverts. Claims go;
-- the memory of the town stays.

CREATE TABLE rt_ruin (
    ruin_id        CHAR(36)    NOT NULL,
    former_town_id CHAR(36)    NOT NULL,
    name           VARCHAR(64) NOT NULL,
    founder_id     CHAR(36),
    ruined_at      BIGINT      NOT NULL,
    expires_at     BIGINT      NOT NULL,
    -- Set when somebody takes the ruin on. A reclaimed ruin is kept rather than deleted, because
    -- "this town is a reclaimed ruin of that one" is exactly the history the anti-recreation rule
    -- and a civic record both want.
    reclaimed_at   BIGINT,
    reclaimed_by   CHAR(36),
    reclaimed_as   CHAR(36),
    PRIMARY KEY (ruin_id),
    CONSTRAINT uq_rt_ruin_former_town UNIQUE (former_town_id)
);

CREATE INDEX idx_rt_ruin_expiry ON rt_ruin (expires_at);

-- The chunks a ruin still holds.
--
-- A separate table from rt_claim rather than a nullable town_id on it. rt_claim.town_id is NOT NULL
-- and carries a foreign key to rt_town; making it nullable to accommodate ruins would weaken the
-- constraint that stops an ordinary claim being orphaned, to describe a state that is not a claim
-- at all. Rows are deleted when the ruin expires or is reclaimed.
CREATE TABLE rt_ruin_claim (
    world_id CHAR(36) NOT NULL,
    chunk_x  INTEGER  NOT NULL,
    chunk_z  INTEGER  NOT NULL,
    ruin_id  CHAR(36) NOT NULL,
    PRIMARY KEY (world_id, chunk_x, chunk_z),
    CONSTRAINT fk_rt_ruin_claim_ruin FOREIGN KEY (ruin_id) REFERENCES rt_ruin (ruin_id) ON DELETE CASCADE
);

CREATE INDEX idx_rt_ruin_claim_ruin ON rt_ruin_claim (ruin_id);
