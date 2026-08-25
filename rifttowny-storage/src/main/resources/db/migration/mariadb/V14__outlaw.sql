-- Players a town has declared unwelcome.
--
-- The structural mirror of rt_town_trust, and deliberately so: trust and outlawry are the same
-- shape of fact from opposite ends. Both are many-to-many between a town and a player who is by
-- definition not one of its residents, both are the town's own opinion rather than the server's,
-- and neither belongs on rt_resident - which holds exactly one town per player and could not
-- express "unwelcome in four towns" at all.
--
-- Who declared it is kept, unlike trust. A trust grant is a favour and nobody asks who gave it; an
-- outlawry is a sanction, and "which of my officers did this" is the first question a mayor asks
-- when a player appeals. Nullable, because an import or an administrative action has no author.

CREATE TABLE rt_town_outlaw (
    town_id     CHAR(36) NOT NULL,
    resident_id CHAR(36) NOT NULL,
    declared_by CHAR(36),
    declared_at BIGINT   NOT NULL,

    PRIMARY KEY (town_id, resident_id),

    -- Answering "where am I unwelcome" without scanning. The player's own side is the one with no
    -- natural index, and it is what a resident's own screen reads.
    KEY idx_rt_town_outlaw_resident (resident_id),

    -- Cascaded: a disbanded town's grudges go with it. Nobody is barred from a town that no longer
    -- exists, and a row that outlived its town would come back when somebody reclaimed the ruin.
    CONSTRAINT fk_rt_town_outlaw_town FOREIGN KEY (town_id)
        REFERENCES rt_town (town_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
