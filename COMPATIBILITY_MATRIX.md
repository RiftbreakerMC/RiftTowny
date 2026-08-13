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
| `/tc` `/nc` `/ac` | — | ⬜ | Phase 5, through RiftChat. Toggles the active channel when given no message |
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

Nothing in this table is implemented yet — the whole surface is Phase 2 work. It is
recorded now so "complete" can be measured against a real list instead of a vague goal.

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
| ruins | post-disband ruin state (`RT-CORE-RUIN`; the land is held, the shell is protected, the chests are not) |

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

RiftTowny's upkeep cycle supplies the "new day" instant. If upkeep is disabled these
resolve to the configured blank value rather than a misleading zero.

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

All resolve in the organisation's default currency. Where RiftEco is absent and
VaultUnlocked is the provider, the single Vault currency is used. Where no economy
provider exists at all, these return the blank value — never `0`, which would read as a
real balance.

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

Location placeholders resolve from the player's **last known claim**, updated by the
movement listener, never by a synchronous chunk lookup during parsing.

### 3.9 Relational (1)

`%rel_townyadvanced_color%` — the colour of the viewed player relative to the viewer:
own town, own nation, ally, enemy, neutral, at war. Colours are configurable and default
to RiftTowny's own palette, not to any other plugin's.

### 3.10 TownyChat compatibility

`%townychat_*%` is served **through RiftChat**, not by a second formatter inside
RiftTowny. RiftTowny supplies channel, role, prefix and relationship context; RiftChat
renders. Status ⬜, Phase 5.

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

## 4. Deliberate non-goals

| Not supported | Why |
|---|---|
| Coexisting with Towny | Namespace collision, stated up front |
| Importing a Towny database | Not requested. Would require reading Towny's on-disk format. Revisit only if asked, and only through Towny's own export |
| PostgreSQL | Out of scope per brief |
| Registering `/rtp` | RiftEssentials owns it |
| A second chat formatter | RiftChat owns formatting |
| A second economy | RiftEco owns balances |
| A second spawner system | RiftSpawners owns spawners |
| A second skill system | mcMMO owns skills |
