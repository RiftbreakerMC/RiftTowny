-- Trusted outsiders.
--
-- A separate table rather than a flag on rt_resident, because a trusted outsider is by definition
-- not a resident of the town trusting them - they are usually a resident of another town, and a
-- single player may be trusted by several. Membership lives on rt_resident.town_id and stays
-- exactly one town; trust is many-to-many and lives here.

CREATE TABLE rt_town_trust (
    town_id     CHAR(36) NOT NULL,
    resident_id CHAR(36) NOT NULL,
    granted_at  BIGINT   NOT NULL,
    PRIMARY KEY (town_id, resident_id),
    CONSTRAINT fk_rt_town_trust_town FOREIGN KEY (town_id) REFERENCES rt_town (town_id) ON DELETE CASCADE
);
CREATE INDEX idx_rt_town_trust_resident ON rt_town_trust (resident_id);
