# Towny behaviour parity

The target: **a player who knows Towny should not have to learn anything new to play RiftTowny.**
Everything Towny does that a player can observe, RiftTowny should do too, plus the bonus systems in
[FEATURE_CATALOG.md](FEATURE_CATALOG.md) that Towny has no equivalent for.

## What "parity" means here, and what it cannot mean

RiftTowny is a clean-room implementation. That is not a preference; it is the constraint the project
was started under, and it draws a hard line through the middle of the word "parity":

| Allowed, and the goal | Forbidden, and not negotiable |
|---|---|
| Matching **observable behaviour** — what a command does, what a rule refuses, what a player sees happen | Copying **code**, in whole or in part |
| Matching **command names and shapes**, so muscle memory carries over | Copying **message text**. Every string in `messages.yml` is written here |
| Matching **the `%townyadvanced_*%` placeholder surface**, so existing scoreboards keep working — see [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md) | Copying **configuration keys or file layout**. `config.yml` is organised around our own systems |
| Reading Towny's **public documentation** to learn what a feature does | Copying **internal design** — its class structure, its storage schema, its algorithms |

So this document tracks the first column. Where RiftTowny deliberately differs, the row says so and
says why; a difference that is not written down here is a bug, not a decision.

## Status vocabulary

| Status | Means |
|---|---|
| **DONE** | Behaviour matches, and is tested |
| **PARTIAL** | The core works; the row names what is missing |
| **MISSING** | Not built |
| **DIFFERENT** | Deliberately not Towny's behaviour, with the reason in the row |

Counts are refreshed when this file is edited, not automatically — treat them as a summary, and the
rows as the truth.

---

## 1. Residents

| Towny behaviour | RiftTowny | Status |
|---|---|---|
| Player is registered on first join | Registered on first action, not on login | **DIFFERENT** — a row per login fills the table with people who never join anything. Names are still cached on join |
| `last_known_name` follows renames | `ResidentPresenceListener` updates it on join | DONE |
| `/resident` — view your own record | — | **MISSING** |
| `/resident <name>` — view another | — | **MISSING** |
| `/resident list` | — | **MISSING** |
| `/resident set mode …` — chat modes, map modes, spy | — | **MISSING** |
| `/resident friend add|remove` | — | **MISSING** — the town trust list exists, personal friends do not |
| `/resident toggle` — bordertitles, plotborder, etc. | — | **MISSING** |
| `/resident jail …` | — | **MISSING** — `RT-MOD-JUSTICE` |
| Resident tax charged per day | — | **MISSING** — `RT-MOD-TAX` |

## 2. Towns

| Towny behaviour | RiftTowny | Status |
|---|---|---|
| `/town new <name>` | `/town new` | DONE |
| `/town` / `/town <name>` — info screen | `/town info` | PARTIAL — no residents list, no bank balance, no board, no per-flag summary |
| `/town add <player>` sends an invite; `/accept` | `/town add`, `/town accept`, `/town deny`, `/town invites` | DONE — answered with `/town accept` rather than a global `/accept` |
| `/town kick <player>` | `/town kick` | DONE |
| `/town leave` | `/town leave` | DONE |
| `/town rename` | `/town rename` | DONE |
| `/town set mayor <player>` | `/town mayor` | DONE |
| `/town delete` | `/town delete` | DONE — and leaves a ruin |
| `/town claim`, `/town claim outpost` | `/town claim [outpost]` | DONE |
| `/town unclaim`, `/town unclaim all` | `/town unclaim` | PARTIAL — no `all` |
| `/town set homeblock` | `/town homeblock` | DONE |
| `/town set spawn` / `/town spawn` | `/town setspawn`, `/town spawn`, `/town delspawn` | PARTIAL — no public spawns, no cost, no travel to other towns |
| Spawn warmup, cooldown, cancel on move/damage | Warmup and cooldown, cancelled by moving a block or being hit | DONE |
| `/town toggle pvp|fire|explosion|mobs` | `/town flag set|clear|here|list` | DONE — a general flag system rather than four toggles, and per-chunk as well as town-wide |
| `/town set perm …` — resident/ally/outsider build/destroy/switch/itemuse | Covered by the flag system's relationship ladder | DONE |
| `/town set board` | — | **MISSING** |
| `/town set tag` | — | **MISSING** |
| `/town rank add|remove` | `/town role assign|unassign` | DONE |
| Custom ranks with configurable permissions | `/town role new|delete`, grant, revoke, reprioritise | DONE — Towny's ranks are config-defined; ours are per-town and editable in game |
| `/town buy bonus`, `/town claim` cost | — | **MISSING** — `RT-MOD-BANK` |
| `/town deposit`, `/town withdraw` | — | **MISSING** — `RT-MOD-BANK` |
| Town upkeep, bankruptcy, ruin on unpaid upkeep | Ruins exist; upkeep does not | PARTIAL |
| `/town outlaw add|remove` | — | **MISSING** |
| `/town merge` | — | **MISSING** |
| `/town online` | — | **MISSING** |
| `/town list` with sorting | — | **MISSING** |
| `/town trust add|remove` | Trust exists in the domain and has no command | PARTIAL |
| `/town purge` | — | **MISSING** |

## 3. Nations

| Towny behaviour | RiftTowny | Status |
|---|---|---|
| `/nation new <name>` | `/nation new` | DONE |
| `/nation` — info screen | `/nation info` | PARTIAL — no town list, no bank balance |
| `/nation add <town>` invites; town accepts | `/nation invite`, `/nation join`, `/nation withdraw`, `/nation invites` | DONE |
| `/nation kick <town>` | `/nation expel` | DONE |
| `/nation leave` (town side) | `/nation leave` | DONE |
| `/nation set capital` | `/nation capital` | DONE |
| `/nation set king` | `/nation king` | DONE |
| `/nation rename` | `/nation rename` | DONE |
| `/nation delete` | `/nation delete` | DONE |
| `/nation rank add|remove` | `/nation role assign|unassign` | DONE |
| `/nation ally add|remove`, enemies, neutrality | — | **MISSING** — `RT-MOD-DIPLOMACY` |
| `/nation deposit`, `/nation withdraw`, nation tax | — | **MISSING** — `RT-MOD-BANK`, `RT-MOD-TAX` |
| `/nation set spawn`, `/nation spawn` | — | **MISSING** |
| `/nation online`, `/nation list` | — | **MISSING** |
| `/nation set board`, `/nation set tag` | — | **MISSING** |

## 4. Plots

| Towny behaviour | RiftTowny | Status |
|---|---|---|
| `/plot claim` — a resident takes a plot | `/plot claim` | DONE |
| `/plot unclaim` | `/plot unclaim` | DONE |
| `/plot` — info | `/plot info` | DONE |
| Plot owner outranks other residents on their own plot | `Relationship.RESIDENT` | DONE |
| `/plot set <type>` — shop, farm, arena, embassy, wilds, inn, jail, bank | `/plot set <type>`, ten types | PARTIAL — recorded and shown; no type carries behaviour yet |
| `/plot forsale <price>`, `/plot notforsale` | — | **MISSING** — `RT-MOD-PROPERTY`, needs an economy |
| `/plot set perm …` per plot | Per-chunk flag overrides via `/town flag here` | DONE |
| `/plot toggle pvp|fire|explosion|mobs` | `/town flag here` | DONE |
| `/plot group …` | — | **MISSING** — areas |
| `/plot district …` | — | **MISSING** |
| `/plot evict` | `/plot unclaim` by somebody with `MANAGE_PLOTS` | DONE |
| `/plot trust add|remove` | — | **MISSING** |

## 5. Territory and protection

| Towny behaviour | RiftTowny | Status |
|---|---|---|
| Build/destroy/switch/itemuse protection by relationship | Full flag resolver, seven ordered layers | DONE |
| Wilderness is unprotected by default | Same | DONE |
| Explosions, fire, mob spawning, piston protection in towns | Same, plus fluid flow across borders | DONE |
| Border title on entering a town | Action-bar notice on entering a town, wilderness, ruin or plot | DONE |
| `/towny map` | — | **MISSING** |
| Outposts | `/town claim outpost` | DONE |
| Claim contiguity | Breadth-first sweep from anchors | DONE |
| Ruined towns: unprotected, reclaimable, then deleted | Same | DONE |
| `/town reclaim` | `/town reclaim` | DONE — restores the town under its own name and id |
| Wilderness regeneration after unclaim | — | **MISSING** — `RT-MOD-REGEN` |
| War-time claim rules | — | **MISSING** — RiftWars |

## 6. Systems with no command surface yet

| Towny behaviour | RiftTowny | Status |
|---|---|---|
| Economy: town/nation banks, taxes, upkeep, plot prices | — | **MISSING** — `RT-MOD-BANK`, `RT-MOD-TAX`. Blocks the reclaim price, plot sales, spawn cost and claim cost rows above |
| Chat channels: town, nation, ally | — | **MISSING** — `RT-MOD-CHAT` |
| `%townyadvanced_*%` placeholders | Manifest captured, none implemented | **MISSING** — `RT-MOD-PAPI` |
| Dynmap/BlueMap/squaremap territory rendering | — | **MISSING** — `RT-MOD-MAP` |
| Jails, outlaws, courts | — | **MISSING** — `RT-MOD-JUSTICE` |
| Discord relay | — | **MISSING** — `RT-MOD-DISCORD` |
| `/townyadmin …` — the whole administrative surface | `/rifttowny status` only | PARTIAL |
| Importing an existing Towny database | — | **MISSING** — `RT-MOD-MIGRATE`, and the thing a real server needs before it can switch |

## 7. Where RiftTowny already goes further

Not parity — these are the reasons the project exists. Each is specified in
[FEATURE_CATALOG.md](FEATURE_CATALOG.md) and none is built yet unless marked.

- **Folia support**, and a scheduler abstraction that makes it real rather than nominal — **built**
- **Per-chunk and per-relationship flags** as one system, rather than a fixed set of toggles — **built**
- **Two-sided joining** for both towns and nations, with expiry — **built**
- **Roles editable in game**, per organisation, with three escalation guards — **built**
- **A ruin that keeps its record** after the land reverts, for anti-recreation and regeneration — **built**
- RiftWars: sieges, war profiles, seasons
- RiftInfrastructure: facilities, logistics, reconstruction
- RiftCivics: treaties, courts, elections, finance, administrative divisions
- Bedrock parity through Floodgate forms, with a test that no action is GUI-only
- MariaDB-backed multi-server networks with a transactional outbox

---

## Honest summary

Counted by row: roughly **half** of Towny's observable surface is DONE, a quarter PARTIAL, a quarter
MISSING. The missing quarter is not evenly spread — it is concentrated in four blocks, and three of
them are one dependency:

1. **Economy** (`RT-MOD-BANK`) blocks taxes, upkeep, plot sales, claim costs, spawn costs and the
   reclaim price. It is the single highest-value thing left.
2. **`/resident`, `/town list`, `/nation list`, `/towny map`** — the read-only surface. Cheap, and
   the most visible absence for a player who knows Towny.
3. **Placeholders and chat** — what makes a server's existing scoreboards and channels keep working.
4. **Migration** — no server can switch to RiftTowny without importing its Towny database.

A server could not replace Towny with RiftTowny today. It could run a new world on it.
