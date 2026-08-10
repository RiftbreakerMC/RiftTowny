-- RiftTowny baseline schema (SQLite).
--
-- Kept deliberately close to the MariaDB copy so the two dialects cannot drift. The differences
-- are only where SQLite forces them: INTEGER PRIMARY KEY AUTOINCREMENT instead of BIGINT
-- AUTO_INCREMENT, no engine or charset clause, and no per-column COMMENT.
--
-- Two conventions apply throughout:
--   * UUIDs are CHAR(36) text in both dialects. BINARY(16) would be smaller and faster, but it
--     makes SQLite rows unreadable during development, which is what this backend is for.
--   * Instants are BIGINT epoch milliseconds in both dialects. Storing a real datetime type would
--     mean two sets of timezone semantics and two sets of conversion bugs.

CREATE TABLE rt_resident (
    resident_id      CHAR(36)     NOT NULL PRIMARY KEY,
    last_known_name  VARCHAR(32)  NOT NULL,
    town_id          CHAR(36)     NULL,
    joined_at        BIGINT       NOT NULL,
    last_seen_at     BIGINT       NOT NULL
);
CREATE INDEX idx_rt_resident_town ON rt_resident (town_id);

-- name_normalised is the uniqueness key; name keeps the operator's capitalisation. Without the
-- split, "Riftholm" and "riftholm" would be two towns that look identical in chat.
CREATE TABLE rt_town (
    town_id           CHAR(36)     NOT NULL PRIMARY KEY,
    name              VARCHAR(32)  NOT NULL,
    name_normalised   VARCHAR(32)  NOT NULL,
    nation_id         CHAR(36)     NULL,
    leader_id         CHAR(36)     NULL,
    bank_account_id   CHAR(36)     NOT NULL,
    homeblock_world   CHAR(36)     NULL,
    homeblock_chunk_x INTEGER      NULL,
    homeblock_chunk_z INTEGER      NULL,
    created_at        BIGINT       NOT NULL,
    CONSTRAINT uq_rt_town_name UNIQUE (name_normalised)
);
CREATE INDEX idx_rt_town_nation ON rt_town (nation_id);

CREATE TABLE rt_nation (
    nation_id       CHAR(36)     NOT NULL PRIMARY KEY,
    name            VARCHAR(32)  NOT NULL,
    name_normalised VARCHAR(32)  NOT NULL,
    leader_id       CHAR(36)     NULL,
    capital_town_id CHAR(36)     NULL,
    bank_account_id CHAR(36)     NOT NULL,
    created_at      BIGINT       NOT NULL,
    CONSTRAINT uq_rt_nation_name UNIQUE (name_normalised)
);

-- The unique constraint on (world, x, z) is what makes overlapping claims impossible at the
-- storage layer rather than only in the service layer.
CREATE TABLE rt_claim (
    claim_id   CHAR(36)     NOT NULL PRIMARY KEY,
    world_id   CHAR(36)     NOT NULL,
    chunk_x    INTEGER      NOT NULL,
    chunk_z    INTEGER      NOT NULL,
    town_id    CHAR(36)     NOT NULL,
    claim_kind VARCHAR(24)  NOT NULL,
    claimed_at BIGINT       NOT NULL,
    CONSTRAINT uq_rt_claim_chunk UNIQUE (world_id, chunk_x, chunk_z),
    CONSTRAINT fk_rt_claim_town FOREIGN KEY (town_id) REFERENCES rt_town (town_id) ON DELETE CASCADE
);
CREATE INDEX idx_rt_claim_town ON rt_claim (town_id);

-- 3D areas inside a claim. Non-overlap within a town is a service-layer rule: expressing it as a
-- constraint would need range types, which neither dialect has.
CREATE TABLE rt_area (
    area_id    CHAR(36)     NOT NULL PRIMARY KEY,
    town_id    CHAR(36)     NOT NULL,
    claim_id   CHAR(36)     NOT NULL,
    name       VARCHAR(32)  NOT NULL,
    min_x      INTEGER      NOT NULL,
    min_y      INTEGER      NOT NULL,
    min_z      INTEGER      NOT NULL,
    max_x      INTEGER      NOT NULL,
    max_y      INTEGER      NOT NULL,
    max_z      INTEGER      NOT NULL,
    created_at BIGINT       NOT NULL,
    CONSTRAINT fk_rt_area_town FOREIGN KEY (town_id) REFERENCES rt_town (town_id) ON DELETE CASCADE,
    CONSTRAINT fk_rt_area_claim FOREIGN KEY (claim_id) REFERENCES rt_claim (claim_id) ON DELETE CASCADE
);
CREATE INDEX idx_rt_area_claim ON rt_area (claim_id);

-- scope tells a town role from a nation role; organisation_id is the town or nation UUID.
-- priority orders roles, and a role may only manage strictly lower priorities.
CREATE TABLE rt_role (
    role_id         CHAR(36)     NOT NULL PRIMARY KEY,
    scope           VARCHAR(8)   NOT NULL,
    organisation_id CHAR(36)     NOT NULL,
    name            VARCHAR(32)  NOT NULL,
    name_normalised VARCHAR(32)  NOT NULL,
    display_name    VARCHAR(64)  NOT NULL,
    icon            VARCHAR(64)  NULL,
    chat_prefix     VARCHAR(64)  NULL,
    priority        INTEGER      NOT NULL,
    system_role     INTEGER      NOT NULL DEFAULT 0,
    created_at      BIGINT       NOT NULL,
    CONSTRAINT uq_rt_role_name UNIQUE (organisation_id, name_normalised)
);
CREATE INDEX idx_rt_role_org ON rt_role (organisation_id, priority);

CREATE TABLE rt_role_permission (
    role_id    CHAR(36)     NOT NULL,
    permission VARCHAR(64)  NOT NULL,
    granted    INTEGER      NOT NULL DEFAULT 1,
    PRIMARY KEY (role_id, permission),
    CONSTRAINT fk_rt_role_permission_role FOREIGN KEY (role_id) REFERENCES rt_role (role_id) ON DELETE CASCADE
);

CREATE TABLE rt_role_member (
    role_id     CHAR(36) NOT NULL,
    resident_id CHAR(36) NOT NULL,
    granted_at  BIGINT   NOT NULL,
    PRIMARY KEY (role_id, resident_id),
    CONSTRAINT fk_rt_role_member_role FOREIGN KEY (role_id) REFERENCES rt_role (role_id) ON DELETE CASCADE
);
CREATE INDEX idx_rt_role_member_resident ON rt_role_member (resident_id);

-- Balances live in RiftEco. What RiftTowny owns is which currencies an organisation may hold and
-- which is its default, stored as stable currency ids and never as display names.
CREATE TABLE rt_organisation_currency (
    scope           VARCHAR(8)   NOT NULL,
    organisation_id CHAR(36)     NOT NULL,
    currency_id     VARCHAR(64)  NOT NULL,
    is_default      INTEGER      NOT NULL DEFAULT 0,
    approved_at     BIGINT       NOT NULL,
    PRIMARY KEY (organisation_id, currency_id)
);

-- Written in the same transaction as the state change it describes, so there is no window where
-- the change happened but the announcement was never queued.
CREATE TABLE rt_outbox (
    id             INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    event_id       CHAR(36)     NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    correlation_id VARCHAR(64)  NULL,
    created_at     BIGINT       NOT NULL,
    available_at   BIGINT       NOT NULL,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL,
    claimed_by     VARCHAR(64)  NULL,
    claimed_at     BIGINT       NULL,
    last_error     TEXT         NULL,
    CONSTRAINT uq_rt_outbox_event UNIQUE (event_id)
);
CREATE INDEX idx_rt_outbox_dispatch ON rt_outbox (status, available_at);
CREATE INDEX idx_rt_outbox_correlation ON rt_outbox (correlation_id);

CREATE TABLE rt_idempotency (
    idempotency_key VARCHAR(160) NOT NULL PRIMARY KEY,
    scope           VARCHAR(64)  NOT NULL,
    result          TEXT         NULL,
    created_at      BIGINT       NOT NULL,
    completed_at    BIGINT       NULL
);
CREATE INDEX idx_rt_idempotency_created ON rt_idempotency (created_at);

CREATE TABLE rt_audit (
    id             INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    occurred_at    BIGINT       NOT NULL,
    server_id      VARCHAR(64)  NOT NULL,
    actor_id       CHAR(36)     NULL,
    actor_name     VARCHAR(32)  NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    subject_type   VARCHAR(32)  NOT NULL,
    subject_id     CHAR(36)     NULL,
    subject_name   VARCHAR(64)  NULL,
    previous_value TEXT         NULL,
    new_value      TEXT         NULL,
    metadata       TEXT         NULL
);
CREATE INDEX idx_rt_audit_subject ON rt_audit (subject_type, subject_id);
CREATE INDEX idx_rt_audit_occurred ON rt_audit (occurred_at);
