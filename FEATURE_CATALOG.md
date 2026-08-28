# Feature Catalog

The register of every feature in the RiftTowny ecosystem. One row per feature, with a
stable ID that never changes even if the feature is renamed, re-homed or re-scheduled.

**A row in this file is a commitment to build, not a claim that it exists.** The `Status`
column is the only thing that says what is real. Read it before assuming anything works.

Last updated 2026-08-10.

---

## 0. How to read this

### 0.1 ID scheme

`<PLUGIN>-<AREA>-<NNN>` — `RT` RiftTowny, `RS` RiftSeasons, `RW` RiftWars,
`RI` RiftInfrastructure, `RC` RiftCivics. IDs are permanent and never reused.

**Renamed once, on 2026-08-10:** `RT-CORE-PROTECT` → `RT-CORE-FLAGS`. The old ID read as the
CoreProtect plugin and was misread as such in conversation; it is the flag resolver and the
protection listeners, and has nothing to do with any third-party plugin. `RT-CORE-PROTECT` is
retired and will not be reused for anything else.

### 0.2 Status vocabulary

| Status | Means |
|---|---|
| `SHIPPED` | Implemented, tested, documented, and in a released jar |
| `ACTIVE` | Being built right now |
| `PLANNED` | Designed or scheduled, no code |
| `BLOCKED` | Cannot be built; the blocker is named in the row |

### 0.3 Universal acceptance criteria

Every feature must satisfy all of these before its status may become `SHIPPED`. Rows list
only their **additional** criteria.

1. `mvn clean verify` green on Java 25 with `-Xlint:all -Werror`.
2. Unit tests for the rules; integration tests against a real database for anything
   persistent.
3. No synchronous database or network call reachable from a server thread. No placeholder
   that blocks on storage.
4. Paper and Folia scheduling rules respected; positional work region-scheduled.
5. Reachable four ways: command with complete tab completion, clickable Adventure chat,
   Java inventory GUI, Floodgate Bedrock form. **No feature is GUI-only.**
6. Migrations written for both dialects and applied in a test.
7. Failure handling: what happens on restart mid-operation, on a missing integration, and
   on a partial write is decided and tested.
8. Permissions checked at the service boundary, not only in the command.
9. Monetary and cross-server operations idempotent and audited.
10. Commands, permissions, configuration, schema, API and migration docs updated.
11. No TODO inside anything marked `SHIPPED`.

### 0.4 Conventions that apply to every row

- **Storage** names the tables it owns. `none` means it holds no state of its own.
- **Config** is relative to the owning plugin's data folder.
- **Permissions** follow `<plugin>.<area>.<action>`; `rifttowny.*` is canonical for
  RiftTowny, with optional `towny.*` aliases.
- **UI** is `G` Java GUI, `B` Bedrock form, `C` clickable chat, `T` tab completion.
  All four are required unless the row says why one is meaningless.
- Every mutating feature emits a typed post-event and writes an outbox row. Rows name only the
  events a consumer would care about. **`rt_audit` is declared in the baseline schema and has no
  writer** — the audit trail arrives with RT-CORE-LOG, and no feature should be read as producing
  one today.

---

## 1. RiftTowny — required core

Cannot be disabled. Release: **RiftTowny: Founding**.

| ID | Feature | Requires | Optional | Storage | Era | Config | Commands / Permissions | UI | API / Events | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| RT-CORE-STORAGE | Dialect abstraction, pooling, Flyway migrations, SQLite shared-mode refusal | — | — | `rt_schema_history` | 1 | `config.yml` | `/rifttowny status` · `rifttowny.admin.status` | C T | — | **SHIPPED** |
| RT-CORE-OUTBOX | Transactional outbox: idempotent append, exclusive claim, stale reclaim, retry budget, prune | RT-CORE-STORAGE | VelocitySrv | `rt_outbox` | 1 | `config.yml` | via `/rifttowny status` | C | `OutboxRepository` | **SHIPPED** |
| RT-CORE-IDEM | Idempotency keys taken inside the transaction they guard: claim, prune | RT-CORE-STORAGE | — | `rt_idempotency` | 1 | — | — | — | `CivicTransaction.KeyStore` | **ACTIVE** — the tax run takes a key per town and per resident sweep per period, which is what lets an interrupted run be resumed without charging anyone twice; a daily sweep prunes spent keys. An earlier standalone `IdempotencyStore` with claim/complete/release/prune was deleted: it lived outside the unit of work, so a claim could not share a fate with the effect it guarded. **Outstanding**: no other operation takes a key — see §7a |
| RT-CORE-SCHED | Paper/Folia scheduler abstraction, Soft-Landing-safe task model | — | — | none | 1 | — | — | — | `RiftScheduler`, `RiftTask` | **SHIPPED** (backends unverified on a live server) |
| RT-CORE-CONFIG | Typed configuration, startup validation, MiniMessage message service | — | — | none | 1 | `config.yml`, `messages.yml` | — | — | `MessageService` | **SHIPPED** |
| RT-CORE-API | Public API, version negotiation, capability registry | — | — | none | 1 | — | — | — | `RiftTownyApi`, `CapabilityRegistry` | **SHIPPED** |
| RT-CORE-MODULES | Module register, dependency validation, safe enable/disable, data preservation | RT-CORE-CONFIG | — | `rt_module_state` | 1 | `modules.yml`, `modules/<m>.yml` | `/rifttowny modules` · `rifttowny.admin.modules` | G B C T | `ModuleStateChanged` | PLANNED |
| RT-CORE-RESIDENT | Resident identity, name normalisation, join/leave, last-seen | RT-CORE-STORAGE | RiftCore | `rt_resident` | 1 | `config.yml` | `/resident` `/res` · `rifttowny.resident.*` | G B C T | `ResidentAdmitted/Released`, `ResidentView` | **ACTIVE** — aggregate, name policy, JDBC repository and `/resident` done and tested. The record shows town, standing, roles, plots held, registered date and a last-seen written as an interval rather than a timestamp — deliberately, because a timestamp to the second tells a stranger the shape of somebody's day. **Outstanding**: `/resident list`, chat and map modes, personal friends, UI |
| RT-CORE-TOWN | Town lifecycle: found, rename, transfer mayoralty, disband, merge | RT-CORE-RESIDENT | RiftEco, RiftLogger | `rt_town`, `rt_town_trust` | 1 | `modules/town.yml` | `/town` `/t` · `rifttowny.town.*` | G B C T | `TownRenamed/LeadershipTransferred/TownJoinedNation/TownLeftNation` | **ACTIVE** — aggregate, JDBC repository, `TownService` and `/town` commands done and tested, including consent-based joining through `rt_invitation` and the read-only surface: `/town info [town]`, `/town list [page] [order]` and `/town online`, all answered from the caches protection already reads. **Outstanding**: merge, board, tag, outlaws, and UI |
| RT-CORE-NATION | Nation lifecycle: create, join, leave, capital, transfer kingship, disband | RT-CORE-TOWN | RiftEco | `rt_nation` | 3 | `modules/nation.yml` | `/nation` `/n` · `rifttowny.nation.*` | G B C T | `NationCreated/CapitalMoved/TownJoined/TownLeft/Disbanded` | **ACTIVE** — aggregate, storage, `NationService` and `/nation` done and tested, including two-sided joining through `rt_invitation` and `/nation info [nation]` / `/nation list`, whose people and land are summed through member towns rather than stored. **Outstanding**: allies and enemies (`RT-MOD-DIPLOMACY`), a spendable nation bank, board, tag, UI |
| RT-CORE-CLAIM | Chunk claims, homeblock, outposts, contiguity, preview and dry-run | RT-CORE-TOWN | RiftLogger | `rt_claim` | 1 | `modules/claims.yml` | `/town claim\|unclaim\|preview\|homeblock`, `/plot` `/p` · `rifttowny.town.claim`, `.unclaim`, `.homeblock` | G B C T | `ChunkClaimed/Unclaimed`, `HomeblockMoved` | **ACTIVE** — domain, storage, `TerritoryService`, commands and plots done and tested, plus the chat map (`/town map`): colour says whose, shape says what, drawn from memory on the thread the command arrived on. **Outstanding**: 3D areas and districts |
| RT-CORE-RUIN | Post-disband ruin state, reclaiming, expiry sweep | RT-CORE-CLAIM | RT-MOD-REGEN, RT-MOD-BANK | `rt_ruin`, `rt_ruin_claim` | 1 | `config.yml` (`ruins.*`) | `/town reclaim` · `rifttowny.town.reclaim` | C T | `TownRuined/RuinReclaimed/RuinLapsed` | **ACTIVE** — a disbanded town leaves a ruin holding its land, deliberately unprotected; after a delay anyone townless may rebuild it under its own name and id. **Outstanding**: only disbanding produces one (bankruptcy, inactivity and sieges do not exist yet), no reclaim price until `RT-MOD-BANK`, lapsed land reverts with its buildings intact until `RT-MOD-REGEN`, and no GUI |
| RT-CORE-AREA | 3D areas, non-overlap, containment, districts, plots | RT-CORE-CLAIM | — | `rt_area` | 2 | `modules/areas.yml` | `/plot area *` · `rifttowny.area.*` | G B C T | `AreaCreated/Removed`, `AreaView` | **ACTIVE** — plots are done: held on `rt_claim`, ten types, `/plot`, and the `RESIDENT` relationship rung they exist to reach. **Outstanding**: 3D areas, districts, and any behaviour behind a plot type (`SHOP`, `JAIL`, `BANK` are recorded and read by nobody). Selling and renting are `RT-MOD-PROPERTY`'s |
| RT-CORE-FLAGS | Central flag resolver and every protection listener | RT-CORE-CLAIM, RT-CORE-ROLE | RiftLogger | `rt_flag_override` | 1 | `modules/protection.yml` | `/town set flag`, `/townyworld` · `rifttowny.flag.*`, `rifttowny.bypass` | G B C T | `FlagDecision`, `flagResolver()` on the API | **ACTIVE** — resolver, `ProtectionQuery`, the in-memory caches that feed it, the block/interaction/entity/world listeners, `rt_flag_override` persistence and `/town flag` are done; `rifttowny.bypass` works. **Outstanding**: the `AREA` layer has no source until `RT-CORE-AREA`, and the world and admin layers have a service but no operator command. Listeners are **not runtime-verified** |
| RT-CORE-ROLE | Configurable roles, priority management, system roles, action/management split | RT-CORE-TOWN | LuckPerms | `rt_role`, `rt_role_permission`, `rt_role_member` | 1 | `modules/roles.yml` | `/town role *`, `/nation role *` · `rifttowny.role.*` | G B C T | `RoleChanged`, `RoleView` | **ACTIVE** — domain, storage, `TownRoleService`, `NationRoleService` and both command trees done and tested; the escalation guards are shared rather than copied per scope, and a nation role is revoked when the citizenship that justified it ends. Display name, icon and chat prefix are settable through `role set` and shown in `role list` as of 2026-08-27; before that the three columns were written on every role and settable by nothing, and `renameTo` carried the old display name forward. Both trees carry the full set — `new`, `clone`, `rename`, `priority`, `grant`, `revoke`, `delete`, `assign`, `unassign` — as of 2026-08-26; before that `grant`, `revoke`, `reprioritise`, `clone` and `rename` were service methods with no command on either side, so a role could be created with an empty permission set and never given a single permission. **Outstanding**: UI |
| RT-CORE-CMD | Command framework, tab completion, clickable chat components | RT-CORE-CONFIG | — | none | 1 | — | — | C T | — | **ACTIVE** — tree, router and completion built from one structure, `Surface` declared per action; clickable chat components outstanding. **Not runtime-verified** |
| RT-CORE-UI | GUI framework and Bedrock form foundation, parity test harness | RT-CORE-CMD | RiftCore (menus), Floodgate | none | 1 | `gui/*.yml` | — | G B | — | PLANNED |
| RT-CORE-DASH | `/town` and `/nation` dashboards | RT-CORE-UI | all | none | 1 | `gui/dashboard.yml` | `/town`, `/nation` · `rifttowny.dashboard` | G B C T | — | PLANNED |

## 2. RiftTowny — optional modules

Release: **RiftTowny: Founding** unless noted.

| ID | Feature | Requires | Optional | Storage | Era | Config | Commands / Permissions | UI | API / Events | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| RT-MOD-BANK | Civic banks, multi-currency, deposits, withdrawals, history, disband settlement | RT-CORE-TOWN | **RiftEco** (preferred), VaultUnlocked | `rt_organisation_currency`, `rt_bank_ledger` | 1 | `modules/bank.yml` | `/town bank *`, `/nation bank *` · `rifttowny.bank.*` | G B C T | `CivicDeposit/Withdrawal/Settled` | **ACTIVE** — `Money`, the ledger, balances, history, `/town bank|deposit|withdraw` and the permission split are done and tested, and the civic half needs no economy plugin. **`PlayerWallet` is BLOCKED**: RiftEco is not available to build against, so money cannot cross between a player and an organisation. Multi-currency, nation banks and disband settlement outstanding |
| RT-MOD-TAX | Resident tax, town tax, nation tax, upkeep, bankruptcy | RT-MOD-BANK | RiftEco | `rt_tax_schedule`, `rt_tax_run` | 2 | `modules/tax.yml` | `/town set taxes` · `rifttowny.tax.*` | G B C T | `TaxRunCompleted`, `Bankruptcy` | PLANNED |
| RT-MOD-DIPLOMACY | Alliances, enemies, neutrality, embassies, open borders — **the state RiftWars reads** | RT-CORE-NATION | RiftCivics | `rt_relation`, `rt_relation_request` | 4 | `modules/diplomacy.yml` | `/nation ally|enemy|neutral` · `rifttowny.diplomacy.*` | G B C T | `RelationChanged`, `DiplomacyCapability` | PLANNED |
| RT-MOD-REGEN | Wilderness snapshot, gradual restore, persistent queue, explosion regen | RT-CORE-CLAIM | RiftLogger, RiftShop, RiftSpawners | `rt_regen_queue`, `rt_regen_snapshot` | 2 | `modules/regeneration.yml` | `/townyadmin regen *` · `rifttowny.admin.regen` | G B C T | `RegenQueued/Completed` | PLANNED |
| RT-MOD-SAFELOC | Dry-land safe-location engine; RTP and wilderness respawn source | RT-CORE-CLAIM | RiftEssentials, RiftPvP | none | 1 | `modules/safelocation.yml` | *no command* — RiftEssentials owns `/rtp` | — | `SafeLocationService`, `ClaimGuard` impl | PLANNED |
| RT-MOD-DESTINATION | Protected destination zones, warp anchors, landing pads | RT-MOD-SAFELOC | maps | `rt_destination` | 2 | `modules/destinations.yml` | `/towny warp`, `/townyadmin destination` · `rifttowny.destination.*` | G B C T | `DestinationCreated/Removed` | PLANNED |
| RT-MOD-LEADERBOARD | Town/nation rankings, daily→lifetime periods, archived organisations | RT-CORE-TOWN | RiftEco, RiftShop, mcMMO | `rt_leaderboard_snapshot` | 2 | `modules/leaderboards.yml` | `/town top`, `/nation top` · `rifttowny.top` | G B C T | `LeaderboardRolled` | PLANNED |
| RT-MOD-FLIGHT | Territory flight eligibility, handoff, no-fly zones, **Soft Landing** | RT-CORE-FLAGS | **RiftBoosters**, RiftPvP | `rt_flight_grant` | 2 | `modules/flight.yml` | `/tfly` `/townyfly` `/townyflight` · `rifttowny.flight.*` | G B C T | `FlightGranted/Revoked`, `SoftLandingStarted` | PLANNED |
| RT-MOD-CHAT | Town/nation/ally channels through RiftChat | RT-CORE-TOWN | RiftChat (optional), VelocitySrv | none | 1 | `messages.yml` (`chat.*`) | `/tc` `/nc` · `rifttowny.chat.town`, `.nation` | C T | recipient selection; RiftChat renders | **ACTIVE** — `/tc` and `/nc` send one message or toggle the channel. Recipients come from `ChannelAudience` over the civic cache; rendering goes to RiftChat when it is installed and to a plain built-in line when it is not. The toggled path narrows `AsyncChatEvent.viewers()` instead of cancelling, so signed chat and every other plugin's chat handling keep applying. No `rt_chat_pref` table: an active channel is a mode you are in for a few minutes, and one that survived a logout would have somebody address their town by accident tomorrow. **Outstanding**: `/ac` — no `ALLY` constant in RiftChat and no allies until `RT-MOD-DIPLOMACY`; no in-game spy, deliberately; and `Permission.CHAT_TOWN` is now checked on both paths — on the command and again on every message of the toggled one, from the civic cache, so revoking chat from a role takes effect on the next line typed. A town role's chat prefix is rendered too. `CHAT_NATION` is checked on the command only, and a nation prefix not at all: nation role books are deliberately uncached |
| RT-MOD-DISCORD | Outbox → Discord announcements via VelocitySrv | RT-CORE-OUTBOX | **VelocitySrv** | reuses `rt_outbox` | 1 | `modules/discord.yml` | `/townyadmin discord *` · `rifttowny.admin.discord` | G B C T | — | PLANNED |
| RT-MOD-DISCORD-CHAN | Per-organisation Discord channels | RT-MOD-DISCORD | VelocitySrv | `rt_discord_channel` | 4 | `modules/discord.yml` | `/town discord create` · `rifttowny.discord.channel` | G B C T | — | **BLOCKED** — VelocitySrv has no channel-creation API. Contract in INTEGRATION_CONTRACTS §2.6 |
| RT-MOD-MAP | One abstraction over BlueMap, Dynmap, squaremap | RT-CORE-CLAIM | three map plugins | none | 1 | `modules/maps.yml` | — · `rifttowny.map` | C T | `MapMarkerProvider` | PLANNED — the *web* maps. The in-game chat map is `/town map` and belongs to RT-CORE-CLAIM |
| RT-MOD-PAPI | `%townyadvanced_*%` + `%rifttowny_*%`, snapshot-cache only | RT-CORE-TOWN | **PlaceholderAPI** | none | 1 | `config.yml` (`placeholders.*`) | — · registered through the capability registry | G B C T | versioned manifest, golden-tested | **ACTIVE** — all 143 `%townyadvanced_*%` names served under Towny's own identifier, plus `Relational` for `%rel_townyadvanced_color%`. Answered from the caches, never storage. **Every name returns a string**, proved by a test sweeping the manifest against a resident, a townless player and no player — a `null` would reach the player as literal `%townyadvanced_…%`. **Outstanding**: balances (need a snapshot cache), the `RT-MOD-PROGRESSION` numbers, districts/plot-groups/for-sale/ally-enemy, and the whole `%rifttowny_*%` native namespace |
| RT-MOD-SHOP | Territory and civic rules for RiftShop; market areas, sales tax | RT-CORE-AREA | **RiftShop**, RT-MOD-BANK | `rt_shop_policy` | 2 | `modules/shops.yml` | `/plot set market` · `rifttowny.shop.*` | G B C T | `ShopPolicyChanged` | PLANNED |
| RT-MOD-SPAWNER | Territory and civic rules for RiftSpawners; spawner areas, caps | RT-CORE-AREA | **RiftSpawners** | `rt_spawner_policy` | 2 | `modules/spawners.yml` | `/plot set spawner` · `rifttowny.spawner.*` | G B C T | `SpawnerPolicyChanged` | PLANNED |
| RT-MOD-MCMMO | Territory XP and ability modifiers within admin-set limits | RT-CORE-CLAIM | **mcMMO** | `rt_skill_modifier` | 2 | `modules/mcmmo.yml` | `/town set skills` · `rifttowny.mcmmo.*` | G B C T | — | PLANNED |
| RT-MOD-PROPERTY | Plot sale, rent, lease, auction, mortgage, embassies | RT-CORE-AREA, RT-MOD-BANK | RiftEco | `rt_property`, `rt_lease` | 2 | `modules/property.yml` | `/plot forsale|claim|rent` · `rifttowny.property.*` | G B C T | `PropertySold/Leased` | PLANNED |
| RT-MOD-ZONING | Zone types, district managers and budgets, permits, valuation, overlays | RT-CORE-AREA | RiftInfrastructure | `rt_zone`, `rt_permit` | 6 | `modules/zoning.yml` | `/plot zone *` · `rifttowny.zone.*` | G B C T | `ZoneChanged` | PLANNED |
| RT-MOD-DEPARTMENT | Town departments and nation ministries, subaccounts, spending limits, audits | RT-MOD-BANK, RT-CORE-ROLE | RiftCivics | `rt_department`, `rt_department_budget` | 4 | `modules/departments.yml` | `/town department *` · `rifttowny.department.*` | G B C T | `DepartmentCreated/BudgetSet` | PLANNED |
| RT-MOD-PROGRESSION | Town/nation level trees, hysteresis, historical peak | RT-CORE-TOWN | RiftEvents | `rt_org_level` | 2 | `modules/progression.yml` | `/town level` · `rifttowny.level` | G B C T | `OrganisationLevelChanged` | PLANNED |
| RT-MOD-CULTURE | Cultures, festivals, monuments, tourism warps, titles, service records | RT-CORE-TOWN | RiftCosmetics, RiftEvents | `rt_culture`, `rt_resident_record` | 4 | `modules/culture.yml` | `/town culture *`, `/resident title` · `rifttowny.culture.*` | G B C T | `CultureChanged` | PLANNED |
| RT-MOD-ONBOARD | Tutorials, town finder, applications, probation, mentors, scholarships | RT-CORE-RESIDENT | RiftEvents, RiftEco, RiftShop | `rt_application`, `rt_mentor` | 1 | `modules/onboarding.yml` | `/town find|apply`, `/resident tutorial` · `rifttowny.onboard.*` | G B C T | `ApplicationSubmitted/Decided` | PLANNED |
| RT-MOD-NPC | Civic service NPCs — never count as residents, voters or soldiers | RT-MOD-FACILITY | NPC provider | `rt_npc` | 6 | `modules/npc.yml` | `/townyadmin npc *` · `rifttowny.npc.*` | G B C T | `NpcSpawned/Removed` | PLANNED |
| RT-MOD-FACILITY | Registered civic facilities, construction, maintenance, service coverage | RT-CORE-AREA | RiftInfrastructure | `rt_facility` | 6 | `modules/facilities.yml` | `/town facility *` · `rifttowny.facility.*` | G B C T | `FacilityRegistered/ConditionChanged` | PLANNED |
| RT-MOD-CALENDAR | Unified schedule of elections, taxes, leases, wars, seasons, events | RT-CORE-TOWN | all | `rt_calendar_entry` | 2 | `modules/calendar.yml` | `/town calendar` · `rifttowny.calendar` | G B C T | `CalendarEntryDue` | PLANNED |
| RT-MOD-INBOX | In-game inbox, offline delivery, quiet hours, subscriptions, urgency | RT-CORE-RESIDENT | VelocitySrv | `rt_inbox` | 1 | `modules/inbox.yml` | `/resident mail` · `rifttowny.inbox.*` | G B C T | `MessageDelivered/Acknowledged` | PLANNED |
| RT-MOD-PORTAL | Scoped read-only web API for the external portal | RT-CORE-API | — | `rt_api_key` | 7 | `modules/portal.yml` | `/rifttowny apikey *` · `rifttowny.admin.apikey` | G B C T | scoped HTTP read API | PLANNED |
| RT-MOD-TEMPLATE | Versioned templates and content packs with diff, lock, rollback | RT-CORE-CONFIG | — | `rt_template` | 1 | `templates/*` | `/rifttowny template *` · `rifttowny.admin.template` | G B C T | — | PLANNED |
| RT-MOD-ERA | Server eras, unlock conditions, population scaling, over-limit freeze | RT-CORE-MODULES | — | `rt_era_state` | 1 | `modules/eras.yml` | `/rifttowny era *` · `rifttowny.admin.era` | G B C T | `EraAdvanced` | PLANNED |
| RT-MOD-JUSTICE | Laws, courts, sheriffs, jails, fines, warrants, outlaws | RT-CORE-ROLE | RiftPunishments, RiftEco | `rt_law`, `rt_case`, `rt_warrant` | 4 | `modules/justice.yml` | `/town law *` · `rifttowny.justice.*` | G B C T | `LawEnacted`, `WarrantIssued` | PLANNED |
| RT-MOD-ADMIN | Simulator, config validator, dependency validator, GUI preview, repair, backup | RT-CORE-MODULES | — | none | 1 | — | `/rifttowny simulate|validate|repair|backup` · `rifttowny.admin.*` | G B C T | — | PLANNED |
| RT-MOD-MIGRATE | Import adapters for Towny, Lands, HuskTowns; SQLite ⇄ MariaDB transfer | RT-CORE-STORAGE | — | none yet | 1 | — | — · `rifttowny.admin.migrate` | C T | `MigrationSource` | **ACTIVE** — `CivicImporter` plus `TownySqlSource`, a reader for Towny’s MySQL database. The importer: never overwrites, idempotent, per-town commit, aggregate-forced ordering, dry run by default, and a report naming everything refused. 18 tests. **Not yet run against a real Towny installation**, and no flatfile reader yet for servers that never moved to MySQL. The design is forced by one finding: RiftTowny disables itself beside Towny, so no importer can use Towny's API and every source must be offline. No `rt_migration_run` table — idempotency comes from the destination already holding the name, which needs no bookkeeping and survives a database restored from backup |

## 3. RiftSeasons

Release: **RiftSeasons: Ages**. Depends on RiftTowny only.

| ID | Feature | Requires | Optional | Storage | Era | Config | Commands / Permissions | UI | API / Events | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| RS-CORE-LIFECYCLE | `PRESEASON → REGISTRATION → CAMPAIGN → FINALS → RECOVERY → ARCHIVE` | RiftTowny API | — | `rs_season`, `rs_transition` | 2 | `config.yml` | `/season status` · `riftseasons.view` | G B C T | `SeasonPhaseChanged` | PLANNED |
| RS-REG-ROSTER | Registration, roster locks, residency requirements, alliance-change limits | RS-CORE-LIFECYCLE | RiftCivics | `rs_registration`, `rs_roster` | 2 | `seasons/registration.yml` | `/season register` · `riftseasons.register` | G B C T | `RosterLocked` | PLANNED |
| RS-SCORE-LEDGER | Component scoring ledger, not just totals | RS-CORE-LIFECYCLE | RiftWars, RiftEvents | `rs_score_entry` | 2 | `seasons/scoring.yml` | `/season standings` · `riftseasons.view` | G B C T | `SeasonScoringContributor` | PLANNED |
| RS-FAIR-LIMITS | Daily opponent limits, anti-farming, anti-snowball, catch-up | RS-SCORE-LEDGER | — | `rs_encounter` | 2 | `seasons/fairness.yml` | — | C | — | PLANNED |
| RS-DIVISION | Divisions and leagues sized by active population | RS-REG-ROSTER | — | `rs_division` | 2 | `seasons/divisions.yml` | `/season division` · `riftseasons.view` | G B C T | — | PLANNED |
| RS-FINALS | Championship battles and finals bracket | RS-DIVISION | RiftWars | `rs_final` | 5 | `seasons/finals.yml` | `/season finals` · `riftseasons.view` | G B C T | `FinalScheduled/Concluded` | PLANNED |
| RS-REWARD | Rewards, Hall of Fame, lifetime history | RS-CORE-LIFECYCLE | RiftEco, RiftCosmetics | `rs_award`, `rs_hall_of_fame` | 2 | `seasons/rewards.yml` | `/season rewards` · `riftseasons.view` | G B C T | `AwardGranted` | PLANNED |
| RS-OVERLAY | Resettable seasonal territory overlay — **never touches permanent claims** | RiftTowny API | — | `rs_overlay_claim` | 5 | `seasons/overlay.yml` | `/season map` · `riftseasons.view` | G B C T | — | PLANNED |
| RS-CLEANUP | Safe end-of-season teardown, archive, holiday pauses | RS-CORE-LIFECYCLE | — | `rs_archive` | 2 | `seasons/cleanup.yml` | `/season admin archive` · `riftseasons.admin` | G B C T | `SeasonArchived` | PLANNED |
| RS-DISCORD | Season reports and standings to Discord | RS-SCORE-LEDGER | VelocitySrv | `rs_outbox` | 2 | `seasons/discord.yml` | — | — | — | PLANNED |

## 4. RiftWars

Release: **RiftWars: Frontier** (Siege only), then **Grand Strategy**.
Full detail in [RIFTWARS_SPECIFICATION.md](RIFTWARS_SPECIFICATION.md).

| ID | Feature | Requires | Optional | Storage | Release | Config | Commands / Permissions | UI | API / Events | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| RW-ENGINE-PROFILE | Composable War Profile engine: participants + justification + theater + objectives + schedule + protection + scoring + consequences | RiftTowny API | — | `rw_profile`, `rw_profile_version` | Frontier | `profiles/*.yml` | `/war profile *` · `riftwars.profile.*` | G B C T | `WarProfileRegistered` | PLANNED |
| RW-ENGINE-SNAPSHOT | Frozen versioned rule snapshot taken at declaration | RW-ENGINE-PROFILE | — | `rw_rule_snapshot` | Frontier | — | `/war status` | G B C T | — | PLANNED |
| RW-ENGINE-OBJECTIVE | Reusable objective components (control, hold, capture, escort, destroy, assemble) | RW-ENGINE-PROFILE | RiftEvents | `rw_objective`, `rw_objective_state` | Frontier | `profiles/objectives.yml` | — | G B C | `ObjectiveStateChanged` | PLANNED |
| RW-ENGINE-SCORING | Component scoring with a full ledger | RW-ENGINE-OBJECTIVE | RiftPvP | `rw_score_entry` | Frontier | `profiles/scoring.yml` | `/war status` | G B C T | `ScoreCommitted` | PLANNED |
| RW-ENGINE-SCHEDULE | Battle windows, timezones, session scheduling | RW-ENGINE-PROFILE | RiftSeasons | `rw_session` | Frontier | `profiles/schedule.yml` | `/war status` | G B C T | `SessionStarted/Ended` | PLANNED |
| RW-SIEGE-LIFECYCLE | `ELIGIBILITY_CHECK → DECLARED → PREPARATION → BATTLE_SESSION → BETWEEN_SESSIONS → RESOLUTION → OCCUPATION_OR_RECOVERY → CLOSED` | RW-ENGINE-* | — | `rw_siege`, `rw_siege_transition` | Frontier | `profiles/siege.yml` | `/war siege *` · `riftwars.siege.*` | G B C T | `SiegeStateChanged` | PLANNED |
| RW-SIEGE-DECLARE | Eligibility validation, war bond escrow, concurrent-declaration safety | RW-SIEGE-LIFECYCLE | **RiftEco** | `rw_declaration` | Frontier | `profiles/siege.yml` | `/war siege declare` · `riftwars.siege.declare` | G B C T | `SiegeDeclared` | PLANNED |
| RW-SIEGE-BANNER | Siege banner and objective zone, terrain validation, restart-safe rebuild | RW-SIEGE-LIFECYCLE | maps | `rw_banner` | Frontier | `profiles/siege.yml` | `/war siege banner` · `riftwars.siege.*` | G B C T | `BannerPlaced/Destroyed` | PLANNED |
| RW-SIEGE-PROTECT | Protection model: authorised objective interactions only, restored after session | RW-SIEGE-LIFECYCLE | — | none | Frontier | `profiles/siege.yml` | — | C | consumes RiftTowny flag resolver | PLANNED |
| RW-SIEGE-OUTCOME | Victory, defeat, draw, surrender, tribute, limited plunder, occupation, immunity | RW-SIEGE-LIFECYCLE | RiftEco, `AssetCapability` | `rw_outcome`, `rw_occupation` | Frontier | `profiles/siege.yml` | `/war surrender` · `riftwars.siege.surrender` | G B C T | `SiegeResolved`, `OccupationStarted/Ended` | PLANNED |
| RW-SHIELD | New-town, recovery, purchased shields; anti-hopping, anti-stacking, caps | RiftTowny API | RiftEco | `rw_shield` | Frontier | `shields.yml` | `/war shield *` · `riftwars.shield.*` | G B C T | `ShieldStarted/Broken/Expired` | PLANNED |
| RW-DISCORD-FEED | Full war feed: global channel, org channels, staff audit, thread per siege, message updates | RW-SIEGE-LIFECYCLE | **VelocitySrv** | `rw_outbox` | Frontier | `discord.yml` | `/war admin feed` · `riftwars.admin.feed` | G B C T | — | PLANNED |
| RW-PAPI | `%riftwars_*%`; SiegeWar-compatible aliases documented separately and **off by default** | RW-SIEGE-LIFECYCLE | PlaceholderAPI | none | Frontier | `placeholders.yml` | — | — | manifest | PLANNED |
| RW-SIMULATE | `/war admin simulate` dry run touching no real balance, standing or siege | RW-ENGINE-* | — | none | Frontier | — | `/war admin simulate` · `riftwars.admin.simulate` | G B C T | — | PLANNED |
| RW-SEASON-LINK | Registration, seasonal windows, divisions, standings contribution | RW-SIEGE-LIFECYCLE | **RiftSeasons** | none | Frontier | `seasons.yml` | `/war season` · `riftwars.season` | G B C T | implements `SeasonScoringContributor` | PLANNED |
| RW-PROFILE-LIBRARY | Border Conflict, Raid, Tournament, Independence, Resource, Economic, Civil, Domination, Frontline | RW-ENGINE-PROFILE | — | reuses engine tables | Grand Strategy | `profiles/*.yml` | `/war profile *` | G B C T | — | PLANNED |
| RW-CAMPAIGN | Multi-front campaigns, fronts, theaters | RW-PROFILE-LIBRARY | RiftSeasons | `rw_campaign` | Grand Strategy | `campaigns.yml` | `/war campaign *` | G B C T | — | PLANNED |
| RW-MILITARY | Armies, divisions, task forces, military roles, rosters, rally points, muster | RW-ENGINE-PROFILE | `LogisticsCapability` | `rw_army`, `rw_unit` | Grand Strategy | `military.yml` | `/war army *` | G B C T | — | PLANNED |
| RW-LOGISTICS | Supply lines, convoys, depots, reinforcement tickets, wartime respawn | RW-MILITARY | **`LogisticsCapability`** | `rw_supply` | Grand Strategy | `logistics.yml` | `/war supply *` | G B C T | — | PLANNED |
| RW-ESPIONAGE | Generated intelligence only — recon, infiltration, counterintel, fog of war, decoys | RW-ENGINE-PROFILE | RiftEco | `rw_intel`, `rw_operation` | Grand Strategy | `espionage.yml` | `/war intel *` | G B C T | `IntelReportGenerated` | PLANNED |
| RW-DOCTRINE | National doctrine trees with tradeoffs, prerequisites, respec cooldowns | RW-ENGINE-PROFILE | RiftEco | `rw_doctrine` | Grand Strategy | `doctrines.yml` | `/war doctrine *` | G B C T | — | PLANNED |
| RW-ANALYTICS | War analytics, after-action reports, historical playback | RW-ENGINE-SCORING | — | `rw_analytics` | Grand Strategy | `analytics.yml` | `/war history` | G B C T | — | PLANNED |

## 5. RiftInfrastructure

Release: **RiftInfrastructure: Industry**. Supplies four capabilities to RiftWars.

| ID | Feature | Requires | Optional | Storage | Era | Config | Commands / Permissions | UI | API / Events | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| RI-ASSET | Stable civic asset IDs, custodians, transfer, retirement, orphan repair | RiftTowny API | RiftShop, RiftSpawners | `ri_asset` | 6 | `assets.yml` | `/civic asset *` · `riftinfra.asset.*` | G B C T | **`AssetCapability`** | PLANNED |
| RI-WAREHOUSE | Warehouses, stockpiles, categories, limits, anti-duplication, reserves | RI-ASSET | — | `ri_warehouse`, `ri_stock` | 6 | `warehouses.yml` | `/civic warehouse *` | G B C T | — | PLANNED |
| RI-PROCURE | Purchase requests, bids, approval workflows, recurring budgets | RI-ASSET | RiftEco | `ri_procurement` | 6 | `procurement.yml` | `/civic procure *` | G B C T | — | PLANNED |
| RI-LOGISTICS | Supply routes, convoys, depots, strategic roads, rails, ports, Nether routes | RI-WAREHOUSE | — | `ri_route`, `ri_convoy` | 6 | `logistics.yml` | `/civic route *` | G B C T | **`LogisticsCapability`** | PLANNED |
| RI-FACILITY | Facility construction, condition, maintenance, inspection, insurance | RiftTowny API | RT-MOD-FACILITY | `ri_facility_state` | 6 | `facilities.yml` | `/civic facility *` | G B C T | **`InfrastructureCapability`** | PLANNED |
| RI-RECONSTRUCT | Repair queues, relief funds, ally aid, insurance payouts, memorials | RI-FACILITY | RiftEvents, RiftEco | `ri_repair_queue` | 6 | `reconstruction.yml` | `/civic rebuild *` | G B C T | **`ReconstructionCapability`** | PLANNED |
| RI-RESOURCE | Resources, industries, production, pollution, depletion, climate | RI-WAREHOUSE | mcMMO | `ri_resource` | 6 | `resources.yml` | `/civic industry *` | G B C T | — | PLANNED |
| RI-PROJECT | Public projects and upgrades, roads, rails, ports, trade hubs | RI-PROCURE | RiftEvents | `ri_project` | 6 | `projects.yml` | `/civic project *` | G B C T | — | PLANNED |

## 6. RiftCivics

Release: **RiftCivics: Nations**. Supplies five capabilities to RiftWars.

| ID | Feature | Requires | Optional | Storage | Era | Config | Commands / Permissions | UI | API / Events | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| RC-GOVERNMENT | Government forms, constitutions, offices, succession | RiftTowny API | — | `rc_government` | 4 | `governments.yml` | `/civics government *` · `riftcivics.gov.*` | G B C T | **`GovernmentApprovalCapability`** | PLANNED |
| RC-ELECTION | Terms, nominations, campaigns, secret ballots, quorum, runoffs, vacancies | RC-GOVERNMENT | — | `rc_election`, `rc_ballot` | 4 | `elections.yml` | `/civics election *` | G B C T | `ElectionConcluded` | PLANNED |
| RC-LEGISLATURE | Legislatures, proposals, referendums, laws, budgets, vetoes, judicial review | RC-ELECTION | — | `rc_proposal`, `rc_law` | 4 | `legislature.yml` | `/civics proposal *` | G B C T | — | PLANNED |
| RC-TREATY | Treaties, pacts, mutual defence, guarantees, vassalage, protectorates, reparations | RT-MOD-DIPLOMACY | RiftEco escrow | `rc_treaty`, `rc_treaty_term` | 4 | `treaties.yml` | `/civics treaty *` | G B C T | advanced **`DiplomacyCapability`** | PLANNED |
| RC-SANCTION | Embargoes, sanctions, tariffs, compliance scoring | RC-TREATY | RiftShop, RiftEco | `rc_sanction` | 4 | `sanctions.yml` | `/civics sanction *` | G B C T | **`SanctionCapability`** | PLANNED |
| RC-PEACE | Peace conferences, ceasefires, armistices, arbitration, war exhaustion | RC-TREATY | RiftWars | `rc_conference` | 5 | `peace.yml` | `/civics peace *` | G B C T | **`PeaceConferenceCapability`** | PLANNED |
| RC-FEDERATION | Federations, coalitions, charters, councils, treasuries, joint command | RC-GOVERNMENT | RiftSeasons, RiftWars | `rc_federation`, `rc_charter` | 7 | `federations.yml` | `/civics federation *` | G B C T | **`FederationCapability`** | PLANNED |
| RC-COUNCIL | World Council, resolutions, global ceasefires, international events | RC-FEDERATION | — | `rc_resolution` | 7 | `council.yml` | `/civics council *` | G B C T | — | PLANNED |
| RC-COURT | Courts, bail, appeals, parole, civil cases, extradition | RT-MOD-JUSTICE | RiftPunishments | `rc_case` | 4 | `courts.yml` | `/civics court *` | G B C T | — | PLANNED |
| RC-CITIZEN | Citizenship, residence, naturalisation, census, passports, visas | RC-GOVERNMENT | — | `rc_citizenship` | 4 | `citizenship.yml` | `/civics citizen *` | G B C T | — | PLANNED |
| RC-FINANCE | Loans, bonds, credit ratings, grants, subsidies, fiscal periods, restructuring | RT-MOD-BANK | RiftEco | `rc_loan`, `rc_bond` | 6 | `finance.yml` | `/civics finance *` | G B C T | — | PLANNED |
| RC-ADMIN-DIV | Provinces, counties, boroughs, wards, addresses, postal codes | RT-CORE-AREA | — | `rc_division` | 6 | `divisions.yml` | `/civics province *` | G B C T | — | PLANNED |

## 7. Long-horizon backlog

Catalogued so nothing is lost, not yet ID'd per feature. Assigned to
**RiftWars: Grand Strategy** or a post-`RiftCivics` release, and gated behind server era 7
where they touch the whole network:

political parties and bicameral chambers · trade fairs, guilds, caravans, shipping ·
transit passes and cross-server travel hubs · public utilities and service districts ·
universities, newspapers, sports, cultural exchange · education and research trees ·
condominiums, inheritance, liens, tenant protection · expeditions, cartography,
archaeology, relic collections · historical map playback and heatmaps · cross-server
organisations, banks and failover · scheduled policy automation · the third-party
objective/leaderboard provider SPI and test toolkit · two-person financial approval,
treasury freezes, scoped API keys, retention and privacy tooling · heraldry and cosmetic
reward surfaces.


## 7a. Written and never read

A sweep on 2026-08-26 for mechanisms that exist, are documented, and are reachable by
nothing. Most were wired or deleted in that pass; what stayed is listed here so it is
tracked rather than merely absent. A row here is a promise the schema or an enum is
making that the code does not yet keep.

| Thing | State | Why it stays |
|---|---|---|
| Nation role decoration and `CHAT_NATION` | A nation's chat prefix is never rendered, and `CHAT_NATION` is checked on the command but not per message | A nation's role book is deliberately not cached — protection reads a town's roles on every block a player touches and never a nation's — so there is nothing to read synchronously inside `AsyncChatEvent`. The town halves of both are done. Caching nation books would close this, and should be weighed against adding an invalidation surface for a chat gate |
| `rt_audit` | Declared in V1, zero writers | Waiting on **RT-CORE-LOG** / RiftLogger. The claims that it was populated have been removed from this file and from `docs/dependency-report.md` |
| `rt_role_permission.granted` | Inserted as a literal `1`, only ever read as `granted = 1` | An explicit deny has no code path: revocation deletes and re-inserts. The column is a constant until a deny case is actually designed |
| `rt_town_spawn.set_by`/`set_at`, `rt_organisation_balance.updated_at`, `rt_resident_preference.updated_at`, `rt_role_member.granted_at` | Written on every save, in no `SELECT` list | Each costs a write and answers nothing. Either a provenance line uses them or they go |
| `rt_area`, `rt_organisation_currency` | No production reference | Named blockers: **RT-CORE-AREA** and **RT-MOD-BANK** multi-currency. Worth noting `rt_organisation_balance` and `rt_bank_ledger` key on a free-text `currency` column that does not reference `rt_organisation_currency`, so the two designs need reconciling when multi-currency lands |
| `TownClaims`' `EMBASSY` contiguity exemption | A live safety exemption for a `PlotType` nothing can currently produce | Needs a test pinning it before something else changes contiguity underneath it |

## 8. Permanently excluded

Never built, in any release. Restated from
[SECURITY_AND_PRIVACY.md](SECURITY_AND_PRIVACY.md) §5 because a catalogue is where someone
would look to add them:

real-money cash-out · cryptocurrency or NFT ownership · donor-only competitive combat
advantage · unrestricted griefing · permanent forced imprisonment · public exact live
player tracking · gameplay access to raw private messages · automatic irreversible claim
or asset deletion · combat power bought with organisation wealth · arbitrary unsandboxed
scripts · automatic era advancement without approval.
