# RiftTowny — Compatibility Matrix

Three separate compatibility surfaces, tracked independently:

1. **Platform** — which servers RiftTowny runs on.
2. **Command** — which familiar commands exist and what they do.
3. **Placeholder** — the `%townyadvanced_*%` manifest, versioned and golden-tested.

Status: `✅ done + tested` · `🟡 partial` · `⬜ planned` · `🚫 blocked` · `❌ deliberately not supported`

---

## 1. Platform

| Target | Status | Note |
|---|---|---|
| Paper 26.1 | ⬜ | `api-version: '26.2'`; 26.1 accepted, verified before release |
| Paper 26.2 | 🟡 | Builds against pinned `26.2.build.92-stable`; runtime verification pending |
| Folia 26.1–26.2 | 🟡 | `folia-supported: true`; all Bukkit access goes through `RiftScheduler`. **No Folia server available in this workspace** — see risk R-07 |
| Java 25 | ✅ | Compiles and tests on Temurin 25.0.3 |
| Java 21 | ❌ | Not supported. The org standardised on 25 |
| Velocity network (multi-backend) | ⬜ | Requires MariaDB. SQLite refuses to start in this mode |
| MariaDB / MySQL | 🟡 | Schema and migrations written; production verification pending |
| SQLite | 🟡 | Single-server only, enforced at startup |
| PostgreSQL | ❌ | Explicitly out of scope |
| Running beside **Towny** | ❌ | Impossible: command tree and PAPI namespace collide. Startup aborts with an explicit message |
| Running beside Lands / HuskTowns | ❌ | Untested and unsupported; overlapping protection would be ambiguous |

---

## 2. Commands

Canonical permission namespace is `rifttowny.*`. `towny.*` aliases are optional and
configuration-gated.

| Command | Aliases | Status | Note |
|---|---|---|---|
| `/town` | `/t` | ⬜ | Central dashboard, Phase 2 |
| `/nation` | `/n` | ⬜ | Central dashboard, Phase 2 |
| `/resident` | `/res` | ⬜ | Phase 2 |
| `/plot` | `/p` | ⬜ | Phase 2 |
| `/towny` | — | ⬜ | Server info, `/towny warp <name>` in Phase 3 |
| `/townyadmin` | `/ta` | ⬜ | Phase 2 |
| `/townyworld` | — | ⬜ | Phase 2 |
| `/tc` `/nc` | — | ✅ | Toggles the active channel when given no message. RiftTowny picks the recipients, RiftChat renders. `/ac` needs `RT-MOD-DIPLOMACY` and a RiftChat `ALLY` constant, and is not built |
| `/tfly` | `/townyfly`, `/townyflight` | ⬜ | Phase 4 |
| `/rtp` `/wild` | — | ❌ **by design** | **Owned by RiftEssentials.** RiftTowny supplies a `ClaimGuard` and its safe-destination API instead of registering a conflicting command |
| `/town top [category]` | — | ⬜ | Phase 3 |
| `/nation top [category]` | — | ⬜ | Phase 3 |
| `/rifttowny` | — | 🟡 | `status` subcommand exists in Phase 1 and reports real integration state |
| `/town discord create` | — | 🚫 | **Blocked**: VelocitySrv has no channel-provisioning API. Contract defined in INTEGRATION_CONTRACTS §2.6 |
| `/nation discord create` | — | 🚫 | Same blocker |

**Interface parity rule.** Every action above must be reachable four ways — command with
full tab completion, clickable Adventure chat, Java inventory GUI, Floodgate Bedrock form.
A parity test enforces this; a GUI-only feature fails the build.

---

## 3. Placeholder manifest — baseline `towny-papi/2026-08-09`

Captured from the official Towny placeholder reference on 2026-08-09. **150 placeholders
in 9 groups.** The manifest is versioned: when Towny adds placeholders, the baseline is
re-captured, the diff is reviewed, and new entries are added as a new manifest version
rather than silently.

**Served. 🟡** The expansion is registered and every name below resolves — `%townyadvanced_*%`,
identifier and all, so an existing scoreboard keeps working unchanged. The list lives as a
resource (`placeholders/townyadvanced.txt`) that both `/papi info` and the golden test read, so
"complete" is measured against a real list rather than a vague goal.

What "resolves" guarantees, exactly: **every name returns a string, never `null`.** Returning
`null` makes PlaceholderAPI leave the literal `%townyadvanced_whatever%` on the player's screen, so
the difference between an unimplemented placeholder rendering blank and rendering as raw markup is
this one rule. A test sweeps all 143 names against three subjects — a player in a town and a
nation, a player in no town, and no player at all — and fails on any `null`.

Roughly seventy carry real values today. The rest return their documented blank, and each blank has
a reason recorded beside it in `TownyPlaceholders`; the groups below say which.

### Global mapping rules

| Towny concept | RiftTowny concept |
|---|---|
| townblock | chunk claim |
| mayor | town leader |
| king | nation leader |
| rank | custom role |
| plot group | area |
| district | district (claim grouping) |
| balance | civic bank balance **in the organisation's default RiftEco currency** |
| peaceful / neutral | town neutrality flag |
| jailed | justice-module detention state (Phase 6; returns the "not jailed" value until then) |
| ruins | post-disband ruin state (`RT-CORE-RUIN`; the land is held but unprotected, and the town can be rebuilt under its own name after a delay) |

Rules that apply to every entry:

- **Never blocks.** Resolution reads an immutable snapshot cache. A cache miss returns the
  configured blank value; it does not query storage.
- **Blank values are exact.** Where Towny returns an empty string, RiftTowny returns an
  empty string — not `none`, not `null`.
- **Booleans** use Towny's own true/false wording, which is configurable there and
  therefore configurable here.
- **`_formatted` vs `_unformatted`** are distinct: formatted applies prefix/postfix and
  colour, unformatted is the raw stored value.
- **Balances** use the organisation's default currency, because these placeholders have
  no currency parameter. `%rifttowny_*%` equivalents accept an explicit currency.

### 3.1 Town & nation prefixes and tags (23)

`town`, `town_formatted`, `town_formatted_with_town_minimessage_colour`,
`town_unformatted`, `town_tag`, `town_tag_override`, `town_tag_unformatted`,
`town_tag_override_unformatted`, `nation_or_town_name`, `nation`, `nation_formatted`,
`nation_formatted_with_nation_minimessage_colour`, `nation_unformatted`, `nation_tag`,
`nation_tag_override`, `nation_tag_town_formatted`, `nation_tag_town_name`,
`nation_tag_unformatted`, `nation_tag_override_unformatted`, `towny_tag`,
`towny_tag_override`, `towny_tag_override_with_minimessage_colour`, `towny_tag_formatted`

### 3.2 Resident (18)

`title`, `surname`, `towny_name_prefix`, `towny_name_postfix`, `towny_prefix`,
`towny_postfix`, `town_ranks`, `nation_ranks`, `resident_primary_rank`,
`resident_primary_rank_spaced`, `player_status`, `towny_colour`,
`resident_join_date_unformatted`, `resident_join_date_formatted`,
`resident_friends_amount`, `has_town`, `has_nation`, `player_jailed`

`town_ranks` / `nation_ranks` map to RiftTowny custom roles; `resident_primary_rank` is
the highest-priority role the resident holds.

### 3.3 Town (19)

`town_residents_amount`, `town_residents_online`, `town_townblocks_used`,
`town_townblocks_bought`, `town_townblocks_bonus`, `town_townblocks_maximum`,
`town_townblocks_natural_maximum`, `town_outposts_claimed`, `town_mayor`, `town_prefix`,
`town_postfix`, `is_town_peaceful`, `is_town_public`, `is_town_open`,
`town_map_color_hex`, `town_map_color_minimessage_hex`, `town_board`,
`town_reclaim_max_duration_hours`, `town_reclaim_min_duration_hours`

### 3.4 Nation (10)

`nation_residents_amount`, `nation_residents_online`, `nation_king`, `nation_capital`,
`nation_prefix`, `nation_postfix`, `nation_map_color_hex`,
`nation_map_color_minimessage_hex`, `is_nation_peaceful`, `nation_board`

### 3.5 New-day timers (7)

`time_until_new_day_hours_raw`, `time_until_new_day_minutes_raw`,
`time_until_new_day_seconds_raw`, `time_until_new_day_formatted`,
`time_until_new_day_hours_formatted`, `time_until_new_day_minutes_formatted`,
`time_until_new_day_seconds_formatted`

RiftTowny's tax run supplies the "new day" instant, floored the same way `TaxPolicy.periodKey`
floors it so the countdown ends exactly when a run becomes due. **Served.** With taxes disabled
these resolve blank rather than to a misleading zero — a countdown to nothing is worse than no
countdown.

**Blank, and why:** `title`, `surname` and the four `towny_*fix` forms are columns `V12` added and
the `Resident` aggregate does not yet carry. `resident_friends_amount` is `0` — there is no friends
subsystem, and town trust is a different thing. `player_jailed` returns the **not-jailed** value
rather than a blank, exactly as the mapping table above promises.

### 3.6 Money (28)

`town_balance`, `nation_balance`, `town_balance_unformatted`,
`nation_balance_unformatted`, `daily_town_upkeep`, `daily_town_upkeep_unformatted`,
`daily_nation_upkeep`, `daily_nation_upkeep_unformatted`, `daily_town_tax`,
`daily_nation_tax`, `daily_town_per_plot_upkeep`,
`daily_town_overclaimed_per_plot_upkeep_penalty`,
`daily_town_upkeep_reduction_from_town_level`,
`daily_town_upkeep_reduction_from_nation_level`, `daily_nation_per_town_upkeep`,
`daily_nation_upkeep_reduction_from_nation_level`, `town_creation_cost`,
`nation_creation_cost`, `townblock_buy_bonus_price`, `townblock_claim_price`,
`townblock_unclaim_price`, `outpost_claim_price`, `townblock_next_claim_price`,
`town_merge_cost`, `town_merge_per_plot_percentage`, `town_reclaim_cost`,
`daily_resident_tax`, `daily_resident_tax_unformatted`

Prices and tax rates are **served** — they are configured values held in memory, so
`town_creation_cost`, `townblock_claim_price`, the upkeeps and the taxes all answer.

**Balances are blank**, and deliberately so: `BankService.balanceOf` returns a future and a
placeholder cannot wait for one. A `0` there would read as a real balance of nothing, which is a
worse lie than saying nothing. They stay blank until a balance snapshot cache exists. The same
applies to `top_town_balance_<n>`.

The level-derived reductions and allowances (`*_reduction_from_*_level`, `townblock_buy_bonus_price`,
`town_merge_*`) belong to `RT-MOD-PROGRESSION`, which is unbuilt. Blank rather than a number,
because any number there is one a town could plan against and lose.

### 3.7 Leaderboard (4, parameterised)

`top_town_balance_<n>`, `top_town_residents_<n>`, `top_town_land_<n>`,
`top_town_residents_and_open_<n>`

Served from the leaderboard snapshot cache (Phase 3). `<n>` beyond the cached depth
returns blank.

### 3.8 Location (34)

`player_plot_type`, `player_plot_owner`, `player_location_town_or_wildname`,
`player_location_formattedtown_or_wildname`, `player_location_town_prefix`,
`player_location_town_postfix`, `player_location_pvp`, `player_location_plot_name`,
`player_location_plot_owner_name`, `player_location_town_resident_count`,
`player_location_town_mayor_name`, `player_location_town_nation_name`,
`player_location_town_board`, `player_location_nation_board`,
`player_location_plotgroup_name`, `player_location_plot_forsale`,
`player_location_town_forsale_cost`, `player_location_in_homeblock`,
`player_location_in_homeblock_owntown`, `player_location_in_homeblock_ownnation`,
`player_location_in_homeblock_enemy`, `player_location_in_homeblock_ally`,
`player_location_district_name`, `player_location_town_map_color_hex`,
`player_location_town_map_color_minimessage_hex`,
`player_location_nation_map_color_hex`,
`player_location_nation_map_color_minimessage_hex`, `player_town_is_trusted`,
`player_plot_is_trusted`, `number_of_towns_in_server`,
`number_of_neutral_towns_in_server`, `number_of_towns_in_world`,
`number_of_neutral_towns_in_world`

**Served**, and they resolve from the player's **last known claim** — recorded by the movement
listener into `LastKnownChunk`, never by a synchronous chunk lookup during parsing. The reason is
not speed: a placeholder is resolved by whatever plugin wants it, on whatever thread it likes, and
reading a player's position from an arbitrary thread is illegal on Folia and racy on Paper.

A player whose position was never recorded resolves blank rather than "Wilderness" — "not standing
anywhere" and "standing in the wild" are different facts, and conflating them puts an offline
player's last position on a live scoreboard.

Blank within this group: districts and plot groups (`RT-CORE-AREA`), for-sale price and flag
(`RT-MOD-PROPERTY`), ally and enemy homeblocks (`RT-MOD-DIPLOMACY`), and `player_location_pvp`
pending a flag-resolution entry point that takes no viewer.

### 3.9 Relational (1)

`%rel_townyadvanced_color%` — the colour of the viewed player relative to the viewer.
**Served, partially**: the expansion implements `me.clip.placeholderapi.expansion.Relational` and
answers with the viewed player's own allegiance colour — their nation's if they have one, their
town's otherwise. The ally / enemy / at-war distinctions need `RT-MOD-DIPLOMACY`, which is unbuilt,
so a stranger and an ally currently look the same rather than looking convincingly different and
being wrong. Colours are RiftTowny's own, set per organisation with `/town set colour`.

### 3.10 TownyChat compatibility

`%townychat_*%` is served **through RiftChat**, not by a second formatter inside
RiftTowny. RiftChat owns formatting; RiftTowny owns who is in what town.

The route turned out to need no RiftChat change at all. RiftChat's
`PlaceholderPresentationService` resolves operator-configured `%…%` expressions through
PlaceholderAPI on a timer, so with the expansion above registered an operator writes the expression
they want into RiftChat's config and town and nation context appears in any channel. Its own
`TownyPresentationService` is unreachable here — it reflects on `com.palmergames.bukkit.towny` and
only wakes when a plugin named `Towny` is enabled — and it is unnecessary. See
INTEGRATION_CONTRACTS §2.5.

Routing `/tc` and `/nc` is RiftTowny's, and needs no upstream change either:
`RiftChatService.Channel` already declares `TOWN` and `NATION`. Status ⬜.

### 3.11 Native namespace

`%rifttowny_*%` covers everything Towny never had: areas, districts, custom roles,
elections and terms, laws, diplomacy and treaties, war state, shields and occupation,
regeneration queues, per-currency balances, flight state, protected destinations,
leaderboard periods (daily/weekly/monthly/seasonal/lifetime) and bounty rankings.
Enumerated in `docs/placeholders.md` as each subsystem lands. Status ⬜.

---

## 3.12 `%riftwars_*%` and the SiegeWar alias question

Owned by RiftWars, not RiftTowny. Native `%riftwars_*%` placeholders ship with
**RiftWars: Frontier**: siege state, participants, current score, session window, next
session, shield status and remaining immunity.

**SiegeWar-compatible aliases are a separate, opt-in, per-alias decision.** They are:

- disabled by default;
- documented in their own manifest, not mixed into the native namespace;
- registered only after a golden test proves the alias's output matches the documented
  behaviour it claims to replace.

Registering an alias blindly would be worse than not offering it: a scoreboard that shows a
plausible but wrong value is harder to debug than one that shows nothing. No compatibility
claim is made for an alias without a passing test.

## 3.13 Plugin-level compatibility

| Plugin | Runs beside | Status |
|---|---|---|
| RiftTowny | Towny | ❌ impossible — namespace collision, startup aborts |
| RiftTowny | Lands, HuskTowns | ❌ untested, unsupported — overlapping protection is ambiguous |
| RiftWars | SiegeWar | ❌ not supported — SiegeWar requires Towny, which RiftTowny excludes |
| RiftWars | RiftTowny | ✅ required dependency |
| RiftWars | RiftSeasons | ⬜ optional; absence disables seasonal scoring only |
| RiftWars | RiftInfrastructure, RiftCivics | ⬜ optional; absence falls back to documented defaults per MODULE_GRAPH §2.2 |
| RiftSeasons | RiftTowny | ✅ required dependency |
| RiftInfrastructure / RiftCivics | RiftTowny | ✅ required dependency |

## 5. Migration — `RT-MOD-MIGRATE`

Status: **the importer and a reader for Towny's MySQL database are built and tested. Not yet run
against a real Towny installation.**

### 5.1 The constraint that decides the design

RiftTowny calls `disablePlugin` on itself when Towny is present — the command tree and the
`%townyadvanced_*%` namespace both collide, and that refusal is enforced in `onEnable`, not merely
documented. **So the two plugins are never running at the same time, and no importer can read Towny
through its API: there is no live Towny to ask.**

Every source is therefore *offline*. It reads what the other plugin left behind — its flatfiles or
its own SQL database — while it is not running. This rules out the design most migration tools use
and is worth stating plainly, because it is not obvious until you try it.

### 5.2 What is built

| Piece | State |
|---|---|
| `MigrationPlan` — a flat, source-agnostic description of another plugin's world | ✅ |
| `MigrationSource` — the one-method interface a reader implements | ✅ |
| `CivicImporter` — validation, ordering, collision handling, dry run, per-town commit | ✅ 18 tests |
| `MigrationReport` — what it did, or would do, and everything it refused | ✅ |
| `TownySqlSource` — a reader for Towny's MySQL database | ✅ 16 tests |
| A reader for Towny's flatfiles | ⬜ not written |

The importer's four rules, each tested:

- **It never overwrites.** A town whose name is already here is skipped and reported. An import is
  run by somebody who does not know exactly what is in the file, against a server that may already
  have towns on it, and the failure mode of guessing is somebody's town quietly becoming somebody
  else's.
- **It is idempotent.** Everything already present is skipped, so a second run imports what the
  first missed and nothing else — which is what makes a partial failure survivable.
- **It commits per town.** One transaction over a whole server would hold locks for its duration and
  throw away every good town because one was bad.
- **The order is forced by the aggregates, not chosen.** Residents, then towns, then nations, then
  claims — because `Town.restore` refuses a mayor who is not a resident and `Nation.restore` refuses
  a capital that is not one of its towns.

Also refused, reported and tested: a town whose mayor is absent from the source, a name this
server's `NamePolicy` rejects (reported rather than silently renamed — a town arriving under a name
its members do not recognise is worse), a claim in a world this server does not have, and two towns
claiming the same chunk.

Townless residents are not imported. RiftTowny registers a resident on first action rather than on
login, so a player record with no town carries nothing this server would not recreate by itself.

### 5.3 Reading Towny's SQL database

The clean-room judgement was taken deliberately and is recorded here: reading another plugin's
persisted data in order to translate *out of* it is ordinary interoperability. RiftTowny's own
schema, design and code are untouched by it — the reader only ever moves data outward, and nothing
about Towny's structure survives into how RiftTowny stores anything.

The schema was read out of Towny's own artifact (`com.palmergames.bukkit.towny:towny:0.103.0.7`)
rather than guessed: `TownySQLSource$TownyDBTableType` for the table names and primary keys,
`SQLSchema` for the columns, `DatabaseConfig` for the `towny_` prefix default.

Two mappings would have been wrong if assumed, and are worth recording:

- **A nation has no leader column.** Towny stores `capital` and takes the king from that town's
  mayor. A guessed `king` column would have produced a reader that compiled, ran, and imported
  every nation leaderless.
- **A town's homeblock lives on the town, not the townblock.** It is a `world,x,z` string on the
  town row; townblock rows carry no flag of their own, so the two are matched back together.

**It reads defensively, and that is the design rather than caution.** Every table is fetched with
`SELECT *` and read by name against the result set's own metadata. Towny's schema grows version by
version; a reader naming its columns in the `SELECT` would fail wholesale on the one version missing
one of them, and would do it *during somebody's migration*. A missing column reads as absent. The
test that matters most is the one loading a deliberately old, narrow schema.

Left out on purpose, each reported to the operator: residents with no UUID (a player cannot be
matched by name alone — the next person to take that name would inherit their town), Towny's own NPC
accounts, towns Towny had already ruined (importing them would resurrect towns whose members had
already lost them), and nations whose capital did not come across.

Still unwritten: a reader for Towny's **flatfile** storage, for servers that never moved to MySQL.
`TownyFlatFileSource` describes that format. The importer and the interchange model are shared, so
it is a second `MigrationSource` and nothing else.

There is **no integration test against a real Towny**, and there cannot be one here: Towny cannot
run beside RiftTowny. The fixtures encode the schema as read out of Towny's artifact, which is the
closest thing available — a first real migration should still be run against a copy of the
database, with the dry run read before applying.

## 4. Deliberate non-goals

| Not supported | Why |
|---|---|
| Coexisting with Towny | Namespace collision, stated up front |
| ~~Importing a Towny database~~ | **No longer a non-goal — requested 2026-08-13.** See §5 |
| PostgreSQL | Out of scope per brief |
| Registering `/rtp` | RiftEssentials owns it |
| A second chat formatter | RiftChat owns formatting |
| A second economy | RiftEco owns balances |
| A second spawner system | RiftSpawners owns spawners |
| A second skill system | mcMMO owns skills |
