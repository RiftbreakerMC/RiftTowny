-- A town's own resident tax rate.
--
-- Nullable, and null is not zero. Null means "this town has not decided", so the server-wide rate in
-- config.yml applies; zero means a town that has deliberately stopped charging its residents, and a
-- server that later raises its default must not start charging that town's people on its behalf.
-- Collapsing the two into a single number is how a town silently starts taking money again.
--
-- Text rather than DECIMAL on SQLite, matching rt_bank_ledger and rt_organisation_balance: amounts
-- are written through Money.toStorage and read back through BigDecimal, and a REAL column would
-- introduce a rounding step between the two.

ALTER TABLE rt_town ADD COLUMN resident_tax TEXT NULL;
