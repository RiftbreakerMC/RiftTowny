-- Drops four bookkeeping columns that were written on every save and read by nothing.
--
--   rt_town_spawn.set_by, set_at        - selected on every read, dropped by the row mapper
--   rt_organisation_balance.updated_at  - the two reads select amount and currency, nothing else
--   rt_resident_preference.updated_at   - both reads select resident_id and territory_notice
--
-- Each cost a write per save and answered no question anybody could ask. None had a rationale in
-- the migration that added it, which is the difference from the columns that stayed: V14 argued for
-- the outlawry's declared_by because "which of my officers did this" is what a mayor faces when a
-- player appeals, and that one is now read and shown. These were added by habit.
--
-- set_by is the one worth naming, because deleting it is a decision rather than tidying. "Who moved
-- our spawn" is a real question. But a town spawn is the only settable town property that had
-- provenance columns at all - the board, the tag, the map colour and openness have none - so
-- keeping them would not have made the question answerable, only answerable for one setting out of
-- five. Provenance for civic changes belongs in the audit trail, uniformly, and that is RT-CORE-LOG
-- with rt_audit already waiting for a writer.
--
-- Two granted_at columns are untouched and stay, both genuinely read: rt_town_trust.granted_at
-- orders trust in ConnectionTownStore, and rt_role_member.granted_at orders role holders in
-- ConnectionRoleStore - "ORDER BY granted_at, resident_id" is what makes first-assigned-first-
-- listed stable, and the save re-uses each holder's original time so an unrelated edit does not
-- collapse them all to one instant.

ALTER TABLE rt_town_spawn DROP COLUMN set_by;
ALTER TABLE rt_town_spawn DROP COLUMN set_at;
ALTER TABLE rt_organisation_balance DROP COLUMN updated_at;
ALTER TABLE rt_resident_preference DROP COLUMN updated_at;
