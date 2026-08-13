-- Plots: what a claimed chunk is for, and who holds it.
--
-- Columns on rt_claim rather than a table beside it, because a plot is not a separate thing from
-- the chunk - it is the same chunk answered from a different angle. The deciding argument is the
-- protection check: "does this player hold the plot they are standing on" is asked on every block a
-- player touches, and keeping it here means the answer comes from the same row that already said
-- which town owns the chunk, rather than from a second table that could disagree with it.
--
-- plot_type is deliberately not claim_kind. One is about use, the other about the town's shape, and
-- marking a chunk as a market must never change whether the town is still contiguous.
--
-- owner_id is nullable and is NOT a foreign key to rt_resident. A plot outlives the person holding
-- it: a resident who leaves has their plots released by the service, but a row that survived a
-- repair or an import should read as "held by somebody unknown" rather than refuse to load.

ALTER TABLE rt_claim ADD COLUMN plot_type VARCHAR(16) NOT NULL DEFAULT 'DEFAULT';
ALTER TABLE rt_claim ADD COLUMN owner_id CHAR(36);

-- "Which plots does this resident hold" is asked whenever somebody leaves a town, and by the plot
-- listing. Without an index it is a scan of every claim on the server.
CREATE INDEX idx_rt_claim_owner ON rt_claim (owner_id);
