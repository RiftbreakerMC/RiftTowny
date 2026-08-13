-- Civic banking: what an organisation holds, and every movement that put it there.
--
-- Two tables because they answer two questions that must not be able to disagree. The balance is
-- what a command shows and what a spend is checked against; the ledger is why it is that number.
-- Both are written in the same transaction, so a balance that does not equal the sum of its ledger
-- is a bug rather than a race.
--
-- Amounts are TEXT, not REAL. A ledger of doubles loses money: 0.1 + 0.2 is not 0.3, and the error
-- compounds over a year of daily taxes into a discrepancy nobody can explain. Stored as the exact
-- decimal string a BigDecimal round-trips.
--
-- Keyed by currency as well as organisation, because RiftEco is multi-currency and a town that
-- holds two of them holds two balances. Servers with one currency simply have one row per town.

CREATE TABLE rt_organisation_balance (
    account_id CHAR(36)    NOT NULL,
    currency   VARCHAR(32) NOT NULL,
    amount     TEXT        NOT NULL,
    updated_at BIGINT      NOT NULL,
    PRIMARY KEY (account_id, currency)
);

-- Every movement, kept forever. A treasury with no history is a number nobody can argue with, and
-- the first question after any large withdrawal is who took it.
--
-- account_id is the organisation's bank account rather than its town or nation id: renaming an
-- organisation or transferring its leadership must never move its money, which is exactly why the
-- account has its own identifier.
-- sequence, not occurred_at, is what "newest first" means here. Two movements in the same
-- millisecond are ordinary - a withdrawal that fails and is put back is two - and ordering by a
-- timestamp would return them in whatever order the ids happened to sort in. A ledger whose entries
-- can appear out of order is one nobody can reconcile.
CREATE TABLE rt_bank_ledger (
    sequence    INTEGER      PRIMARY KEY AUTOINCREMENT,
    entry_id    CHAR(36)     NOT NULL,
    account_id  CHAR(36)     NOT NULL,
    currency    VARCHAR(32)  NOT NULL,
    amount      TEXT         NOT NULL,
    balance     TEXT         NOT NULL,
    reason      VARCHAR(32)  NOT NULL,
    actor_id    CHAR(36),
    detail      VARCHAR(128),
    occurred_at BIGINT       NOT NULL,
    CONSTRAINT uq_rt_bank_ledger_entry UNIQUE (entry_id)
);

-- The history of one account, newest first, which is the only way it is ever read.
CREATE INDEX idx_rt_bank_ledger_account ON rt_bank_ledger (account_id, sequence);
