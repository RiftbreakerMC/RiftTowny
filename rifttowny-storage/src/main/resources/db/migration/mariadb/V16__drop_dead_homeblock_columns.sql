-- Drops three columns that never held anything.
--
-- rt_town.homeblock_world, homeblock_chunk_x and homeblock_chunk_z were declared in the V1 baseline
-- and no code has ever touched them: ConnectionTownStore.COLUMNS omits all three, so nothing wrote
-- one and nothing read one. Every row has held NULL since the first town was founded.
--
-- The homeblock is derived instead, from the claim whose claim_kind is HOMEBLOCK, and that is the
-- representation the aggregate, the contiguity rules and the unclaim guards all work in. Keeping a
-- second one that is permanently NULL is the exact hazard V3's own comment warns about - "two
-- columns describing the same fact drift" - with the added trap that the dead pair looks
-- authoritative to anyone reading the schema before the code.
--
-- Not to be confused with rt_ruin's homeblock_world/homeblock_x/homeblock_z, added in V7, which are
-- live: a ruin has no claims left to derive from, so there the column IS the fact.


ALTER TABLE rt_town
    DROP COLUMN homeblock_world,
    DROP COLUMN homeblock_chunk_x,
    DROP COLUMN homeblock_chunk_z;
