-- What an organisation says about itself, and who it lets in.
--
-- Columns on rt_town and rt_nation rather than a table of their own. Every one of them is a single
-- value per organisation, read whenever the organisation is read, and written by the same command
-- that reads it. A rt_town_profile table would be a join on the hottest load path in the plugin to
-- answer questions that are already answered by the row we just fetched.
--
-- The two halves of this migration are genuinely different in kind, and it is worth being plain
-- about which is which:
--
--   board, tag, map_colour   presentation. Shown, never enforced.
--   neutral                  a DECLARED stance. RiftWars will read it; nothing in RiftTowny does.
--                            Recorded and shown, the same standing as a plot marked SHOP.
--   is_open, public_spawn    behaviour. is_open is what lets somebody join a town that never
--                            invited them; public_spawn is what lets an outsider travel to its
--                            spawn. Both are enforced, or they would not be here.
--
-- Nations get no is_open and no public_spawn. A town joins a nation by invitation on both sides,
-- so there is no such thing as walking in, and a nation has no spawn to open.

-- Board and tag are player-written text. They are stored as typed, minus section signs and control
-- characters, which the domain strips at the boundary: those reach a client below MiniMessage, so
-- no renderer downstream can neutralise them.
ALTER TABLE rt_town ADD COLUMN board        VARCHAR(160);
ALTER TABLE rt_town ADD COLUMN tag          VARCHAR(16);

-- Six lower-case hex digits, no leading hash. NULL means "no colour chosen", which is a different
-- thing from grey: a town that picked grey keeps it if the default ever changes.
ALTER TABLE rt_town ADD COLUMN map_colour   VARCHAR(6);

-- SQLite has no BOOLEAN; the driver maps these to 0 and 1. NOT NULL DEFAULT 0 rather than nullable,
-- because a three-valued "is this town open" would have to be collapsed to false at every read.
ALTER TABLE rt_town ADD COLUMN neutral      INTEGER NOT NULL DEFAULT 0;
ALTER TABLE rt_town ADD COLUMN is_open      INTEGER NOT NULL DEFAULT 0;
ALTER TABLE rt_town ADD COLUMN public_spawn INTEGER NOT NULL DEFAULT 0;

ALTER TABLE rt_nation ADD COLUMN board      VARCHAR(160);
ALTER TABLE rt_nation ADD COLUMN tag        VARCHAR(16);
ALTER TABLE rt_nation ADD COLUMN map_colour VARCHAR(6);
ALTER TABLE rt_nation ADD COLUMN neutral    INTEGER NOT NULL DEFAULT 0;

-- A resident's title and surname, which an administrator grants and which sit either side of the
-- player's name wherever it is shown. Towny has both and they are two of its placeholders; they are
-- decoration, and deliberately not something a player can set for themselves.
ALTER TABLE rt_resident ADD COLUMN title   VARCHAR(16);
ALTER TABLE rt_resident ADD COLUMN surname VARCHAR(16);
