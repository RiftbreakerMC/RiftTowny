-- Outstanding invitations.
--
-- Exists because a town joining a nation needs consent from both sides, and neither one-sided rule
-- is safe. A nation that could conscript a town would move that town's protection relationship
-- without asking it; a town that could attach itself to any nation would walk into every member
-- town's territory as a citizen. So one side offers and the other accepts.
--
-- Generic in the inviter and the invitee rather than a table per pairing. The nation-invites-town
-- case is the one built now; player-invited-to-town is the same shape and lands with the same table
-- rather than a second one that would need its own expiry sweep and its own tests.
--
--   inviter_scope   TOWN | NATION
--   invitee_kind    TOWN | RESIDENT
--
-- The unique key is the pairing, not the row: a second invitation from the same organisation to the
-- same invitee is the same offer repeated, and re-offering should refresh it rather than accumulate.

CREATE TABLE rt_invitation (
    invitation_id  CHAR(36)    NOT NULL,
    inviter_scope  VARCHAR(16) NOT NULL,
    inviter_id     CHAR(36)    NOT NULL,
    invitee_kind   VARCHAR(16) NOT NULL,
    invitee_id     CHAR(36)    NOT NULL,
    created_by     CHAR(36),
    created_at     BIGINT      NOT NULL,
    expires_at     BIGINT      NOT NULL,
    PRIMARY KEY (invitation_id),
    CONSTRAINT uq_rt_invitation_pairing
        UNIQUE (inviter_scope, inviter_id, invitee_kind, invitee_id),
    -- The invitee's question - "who has invited me" - is the one asked on every accept and every
    -- listing, and it is not served by the pairing key, which leads with the inviter.
    INDEX idx_rt_invitation_invitee (invitee_kind, invitee_id),
    -- Expiry is swept in bulk rather than checked per row at read time, so it needs its own index.
    INDEX idx_rt_invitation_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
