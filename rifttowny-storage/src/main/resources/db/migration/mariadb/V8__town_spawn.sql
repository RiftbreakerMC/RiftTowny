-- Where a town's residents arrive.
--
-- A separate table rather than columns on rt_town, and the reason is the shape rather than the size:
-- a spawn is optional, and six nullable columns on the town row make every read of a town carry a
-- position it usually does not want. It also gives nation spawns and, later, warp anchors somewhere
-- to live without another migration.
--
-- The position is stored as plain numbers, matching WorldPosition: a Bukkit Location holds a World
-- reference, and on Folia touching one off its owning region is a crash. Yaw and pitch are kept so
-- an arriving player faces the way the town meant them to, which is the difference between a spawn
-- and a coordinate.
--
-- x, y and z are DOUBLE rather than integers. A spawn is a standing position, not a block: rounding
-- it would put arrivals in the corner of a block and, on a half-slab or a stair, inside one.

CREATE TABLE rt_town_spawn (
    town_id  CHAR(36) NOT NULL,
    world_id CHAR(36) NOT NULL,
    x        DOUBLE   NOT NULL,
    y        DOUBLE   NOT NULL,
    z        DOUBLE   NOT NULL,
    yaw      FLOAT    NOT NULL,
    pitch    FLOAT    NOT NULL,
    set_by   CHAR(36),
    set_at   BIGINT   NOT NULL,
    PRIMARY KEY (town_id),
    CONSTRAINT fk_rt_town_spawn_town FOREIGN KEY (town_id) REFERENCES rt_town (town_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
