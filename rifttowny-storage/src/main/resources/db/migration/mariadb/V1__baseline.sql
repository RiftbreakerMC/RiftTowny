-- RiftTowny baseline schema (MariaDB / MySQL).
--
-- Kept deliberately close to the SQLite copy so the two dialects cannot drift. The differences are
-- only where the dialect forces them: BIGINT AUTO_INCREMENT instead of INTEGER AUTOINCREMENT, and
-- an explicit engine and charset.
--
-- utf8mb4 throughout: town names and MiniMessage board text contain emoji in practice, and utf8mb3
-- truncates them into replacement characters that then fail a later uniqueness check.
--
-- Two conventions apply throughout:
--   * UUIDs are CHAR(36) text in both dialects.
--   * Instants are BIGINT epoch milliseconds in both dialects.

CREATE TABLE rt_resident (
    resident_id      CHAR(36)     NOT NULL PRIMARY KEY,
    last_known_name  VARCHAR(32)  NOT NULL,
    town_id          CHAR(36)     NULL,
    joined_at        BIGINT       NOT NULL,
    last_seen_at     BIGINT       NOT NULL,
    INDEX idx_rt_resident_town (town_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

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
    homeblock_chunk_x INT          NULL,
    homeblock_chunk_z INT          NULL,
    created_at        BIGINT       NOT NULL,
    CONSTRAINT uq_rt_town_name UNIQUE (name_normalised),
    INDEX idx_rt_town_nation (nation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE rt_nation (
    nation_id       CHAR(36)     NOT NULL PRIMARY KEY,
    name            VARCHAR(32)  NOT NULL,
    name_normalised VARCHAR(32)  NOT NULL,
    leader_id       CHAR(36)     NULL,
    capital_town_id CHAR(36)     NULL,
    bank_account_id CHAR(36)     NOT NULL,
    created_at      BIGINT       NOT NULL,
    CONSTRAINT uq_rt_nation_name UNIQUE (name_normalised)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- The unique constraint on (world, x, z) is what makes overlapping claims impossible at the
-- storage layer rather than only in the service layer.
CREATE TABLE rt_claim (
    claim_id   CHAR(36)     NOT NULL PRIMARY KEY,
    world_id   CHAR(36)     NOT NULL,
    chunk_x    INT          NOT NULL,
    chunk_z    INT          NOT NULL,
    town_id    CHAR(36)     NOT NULL,
    claim_kind VARCHAR(24)  NOT NULL,
    claimed_at BIGINT       NOT NULL,
    CONSTRAINT uq_rt_claim_chunk UNIQUE (world_id, chunk_x, chunk_z),
    CONSTRAINT fk_rt_claim_town FOREIGN KEY (town_id) REFERENCES rt_town (town_id) ON DELETE CASCADE,
    INDEX idx_rt_claim_town (town_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 3D areas inside a claim. Non-overlap within a town is a service-layer rule: expressing it as a
-- constraint would need range types, which neither dialect has.
CREATE TABLE rt_area (
    area_id    CHAR(36)     NOT NULL PRIMARY KEY,
    town_id    CHAR(36)     NOT NULL,
    claim_id   CHAR(36)     NOT NULL,
    name       VARCHAR(32)  NOT NULL,
    min_x      INT          NOT NULL,
    min_y      INT          NOT NULL,
    min_z      INT          NOT NULL,
    max_x      INT          NOT NULL,
    max_y      INT          NOT NULL,
    max_z      INT          NOT NULL,
    created_at BIGINT       NOT NULL,
    CONSTRAINT fk_rt_area_town FOREIGN KEY (town_id) REFERENCES rt_town (town_id) ON DELETE CASCADE,
    CONSTRAINT fk_rt_area_claim FOREIGN KEY (claim_id) REFERENCES rt_claim (claim_id) ON DELETE CASCADE,
    INDEX idx_rt_area_claim (claim_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

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
    priority        INT          NOT NULL,
    system_role     TINYINT(1)   NOT NULL DEFAULT 0,
    created_at      BIGINT       NOT NULL,
    CONSTRAINT uq_rt_role_name UNIQUE (organisation_id, name_normalised),
    INDEX idx_rt_role_org (organisation_id, priority)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE rt_role_permission (
    role_id    CHAR(36)     NOT NULL,
    permission VARCHAR(64)  NOT NULL,
    granted    TINYINT(1)   NOT NULL DEFAULT 1,
    PRIMARY KEY (role_id, permission),
    CONSTRAINT fk_rt_role_permission_role FOREIGN KEY (role_id) REFERENCES rt_role (role_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE rt_role_member (
    role_id     CHAR(36) NOT NULL,
    resident_id CHAR(36) NOT NULL,
    granted_at  BIGINT   NOT NULL,
    PRIMARY KEY (role_id, resident_id),
    CONSTRAINT fk_rt_role_member_role FOREIGN KEY (role_id) REFERENCES rt_role (role_id) ON DELETE CASCADE,
    INDEX idx_rt_role_member_resident (resident_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Balances live in RiftEco. What RiftTowny owns is which currencies an organisation may hold and
-- which is its default, stored as stable currency ids and never as display names.
CREATE TABLE rt_organisation_currency (
    scope           VARCHAR(8)   NOT NULL,
    organisation_id CHAR(36)     NOT NULL,
    currency_id     VARCHAR(64)  NOT NULL,
    is_default      TINYINT(1)   NOT NULL DEFAULT 0,
    approved_at     BIGINT       NOT NULL,
    PRIMARY KEY (organisation_id, currency_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Written in the same transaction as the state change it describes, so there is no window where
-- the change happened but the announcement was never queued.
CREATE TABLE rt_outbox (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id       CHAR(36)     NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    correlation_id VARCHAR(64)  NULL,
    created_at     BIGINT       NOT NULL,
    available_at   BIGINT       NOT NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL,
    claimed_by     VARCHAR(64)  NULL,
    claimed_at     BIGINT       NULL,
    last_error     TEXT         NULL,
    CONSTRAINT uq_rt_outbox_event UNIQUE (event_id),
    INDEX idx_rt_outbox_dispatch (status, available_at),
    INDEX idx_rt_outbox_correlation (correlation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE rt_idempotency (
    idempotency_key VARCHAR(160) NOT NULL PRIMARY KEY,
    scope           VARCHAR(64)  NOT NULL,
    result          TEXT         NULL,
    created_at      BIGINT       NOT NULL,
    completed_at    BIGINT       NULL,
    INDEX idx_rt_idempotency_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE rt_audit (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
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
    metadata       TEXT         NULL,
    INDEX idx_rt_audit_subject (subject_type, subject_id),
    INDEX idx_rt_audit_occurred (occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
