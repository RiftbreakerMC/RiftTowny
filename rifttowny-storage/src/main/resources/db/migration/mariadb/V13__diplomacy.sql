-- What nations have declared about each other.
--
-- One row per DECLARATION, not per relationship, and that asymmetry is the design rather than an
-- artefact of it:
--
--   An alliance takes two. Two rows, one each way, and the two nations are allied only when both
--   exist. The ALLY rung grants real access to territory, so one nation must not be able to hand
--   another the run of its land by typing a command the other never saw.
--
--   An enmity takes one. Refusing to be somebody's enemy is not a thing you can do. The single row
--   binds the declarer - it says how THEY will treat the target - and the target is merely told.
--
-- Storing "these two are allied" as one row instead would have made the mutual requirement a
-- convention every caller had to remember rather than something the data enforces.

CREATE TABLE rt_nation_relation (
    declarer_id VARCHAR(36) NOT NULL,
    target_id   VARCHAR(36) NOT NULL,
    relation    VARCHAR(16) NOT NULL,
    declared_at BIGINT      NOT NULL,

    -- The declarer, the target and the kind together: a nation may hold both an ALLY and an ENEMY
    -- row against the same target during a falling-out, and the service is what refuses that -
    -- the table's job is only to stop the same declaration existing twice.
    PRIMARY KEY (declarer_id, target_id, relation),

    -- Answering "who has declared anything about me" without scanning: the target side is the one
    -- with no natural index, and it is what a nation's own relations screen reads.
    KEY idx_rt_nation_relation_target (target_id, relation),

    -- Cascaded, unlike most references here. A dissolved nation's declarations are meaningless in
    -- both directions: nobody is allied to a nation that no longer exists, and nobody should be
    -- left carrying a grudge against nothing.
    CONSTRAINT fk_rt_relation_declarer FOREIGN KEY (declarer_id)
        REFERENCES rt_nation (nation_id) ON DELETE CASCADE,
    CONSTRAINT fk_rt_relation_target FOREIGN KEY (target_id)
        REFERENCES rt_nation (nation_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
