-- Tax runs.
--
-- One row per completed run, and the reason it exists is idempotency rather than history. A tax run
-- is scheduled work that charges every town on the server; running it twice charges everybody twice,
-- and the two ways that happens are both ordinary: an operator restarting a server around the due
-- time, and two backend servers sharing one database both deciding it is their turn.
--
-- The period key is what makes the guard work. It is the run's due time floored to the interval, so
-- every server computes the same key for the same period and exactly one of them wins the insert.
-- Without it, "has it run recently" is a race with a comparison in it.
--
-- Kept forever. A run that charged a town more than it expected is a question somebody asks weeks
-- later, and the answer is in the ledger - but only if there is a run to point at.

CREATE TABLE rt_tax_run (
    period_key    VARCHAR(32) NOT NULL,
    started_at    BIGINT      NOT NULL,
    finished_at   BIGINT,
    towns_charged INTEGER     NOT NULL DEFAULT 0,
    residents_charged INTEGER NOT NULL DEFAULT 0,
    towns_fallen  INTEGER     NOT NULL DEFAULT 0,
    server_id     VARCHAR(64) NOT NULL,
    PRIMARY KEY (period_key)
);

-- How long a town has been unable to pay. Nulled the moment it pays, so a town that misses one run
-- and then recovers is not carrying a debt nobody told it about.
--
-- On rt_town rather than its own table: it is one nullable timestamp per town, it is read whenever
-- the town is, and a separate table would be a join to answer "is this town in trouble".
ALTER TABLE rt_town ADD COLUMN unpaid_since BIGINT;
