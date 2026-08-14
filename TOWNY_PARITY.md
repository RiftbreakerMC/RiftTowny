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
| `/resident` — view your own record | `/resident`, `/res` | DONE |
| `/resident <name>` — view another | `/resident <name>` | DONE |
| `/resident list` | — | **MISSING** — a list of every account is a different screen from a list of towns, and rarely the one somebody wants |
| `/resident set mode …` — chat modes, map modes, spy | — | **MISSING** |
| `/resident friend add|remove` | — | **MISSING** — the town trust list exists, personal friends do not |
| `/resident toggle` — bordertitles, plotborder, etc. | — | **MISSING** |
| `/resident jail …` | — | **MISSING** — `RT-MOD-JUSTICE` |
| Resident tax charged per day | `taxes.resident`, on the configured interval | DONE |

## 2. Towns

| Towny behaviour | RiftTowny | Status |
|---|---|---|
| `/town new <name>` | `/town new` | DONE |
| `/town` / `/town <name>` — info screen | `/town info [town]` | PARTIAL — mayor, founding date, treasury, land, nation, trusted and the resident list are all there; no board and no per-flag summary |
| `/town add <player>` sends an invite; `/accept` | `/town add`, `/town accept`, `/town deny`, `/town invites` | DONE — answered with `/town accept` rather than a global `/accept` |
| `/town kick <player>` | `/town kick` | DONE |
| `/town leave` | `/town leave` | DONE |
| `/town rename` | `/town rename` | DONE |
| `/town set mayor <player>` | `/town mayor` | DONE |
| `/town delete` | `/town delete` | DONE — and leaves a ruin |
| `/town claim`, `/town claim outpost` | `/town claim [outpost]` | DONE |
| `/town unclaim`, `/town unclaim all` | `/town unclaim` | PARTIAL — no `all` |
| `/town set homeblock` | `/town homeblock` | DONE |
| `/town set spawn` / `/town spawn` | `/town setspawn`, `/town spawn`, `/town delspawn` | PARTIAL — priced, but no public spawns and no travel to other towns |
| Spawn warmup, cooldown, cancel on move/damage | Warmup and cooldown, cancelled by moving a block or being hit | DONE |
| `/town toggle pvp|fire|explosion|mobs` | `/town flag set|clear|here|list` | DONE — a general flag system rather than four toggles, and per-chunk as well as town-wide |
| `/town set perm …` — resident/ally/outsider build/destroy/switch/itemuse | Covered by the flag system's relationship ladder | DONE |
| `/town set board` | `/town set board <text\|clear>` | DONE |
| `/town set tag` | `/town set tag <text\|clear>` | DONE |
| `/town toggle open` — anyone may join without an invitation | `/town set open <on\|off>`, and `/town join <town>` | DONE |
| `/town toggle public` — outsiders may use the spawn | `/town set public <on\|off>`, and `/town spawn <town>` | DONE |
| `/town toggle neutral` / peaceful | `/town set neutral <on\|off>` | PARTIAL — recorded and shown; **nothing enforces it**, because there is no war module. RiftWars will read it |
| Town map colour | `/town set colour <#a1b2c3\|clear>` | DONE |
| `/town rank add|remove` | `/town role assign|unassign` | DONE |
| Custom ranks with configurable permissions | `/town role new|delete`, grant, revoke, reprioritise | DONE — Towny's ranks are config-defined; ours are per-town and editable in game |
| `/town claim` cost, and a refund on unclaim | `prices.claim`, `prices.claim-refund`, charged in the same transaction as the claim | DONE |
| `/town buy bonus` | — | **MISSING** |
| `/town deposit`, `/town withdraw` | `/town deposit`, `/town withdraw`, `/town bank` | DONE — bound to RiftEco. Refuses with a clear message when no economy plugin is installed |
| Town upkeep, bankruptcy, ruin on unpaid upkeep | `taxes.upkeep-per-chunk`, a grace period, then a fall into a ruin | DONE |
| Resident tax, and eviction of residents who cannot pay | Resident tax charged; **no eviction** | **DIFFERENT** — eviction is a punishment applied by a timer to somebody who may simply have been away. A town that wants somebody gone has `/town kick` and a person to decide it |
| `/town outlaw add|remove` | — | **MISSING** |
| `/town merge` | — | **MISSING** |
| `/town online` | `/town online [town]` | DONE — with each person's roles beside them |
| `/town list` with sorting | `/town list [page] [name\|residents\|land\|age]` | DONE — page and order in either order, since players type both |
| `/town trust add|remove` | Trust exists in the domain and has no command | PARTIAL |
| `/town purge` | — | **MISSING** |

## 3. Nations

| Towny behaviour | RiftTowny | Status |
|---|---|---|
| `/nation new <name>` | `/nation new` | DONE |
| `/nation` — info screen | `/nation info [nation]` | PARTIAL — leader, founding date, towns, residents, land, capital and the member list; no bank balance until the nation account can be spent |
| `/nation add <town>` invites; town accepts | `/nation invite`, `/nation join`, `/nation withdraw`, `/nation invites` | DONE |
| `/nation kick <town>` | `/nation expel` | DONE |
| `/nation leave` (town side) | `/nation leave` | DONE |
| `/nation set capital` | `/nation capital` | DONE |
| `/nation set king` | `/nation king` | DONE |
| `/nation rename` | `/nation rename` | DONE |
| `/nation delete` | `/nation delete` | DONE |
| `/nation rank add|remove` | `/nation role assign|unassign` | DONE |
| `/nation ally add|remove`, enemies, neutrality | — | **MISSING** — `RT-MOD-DIPLOMACY` |
| Nation tax collected from member towns | `taxes.nation-per-town` | DONE |
| `/nation deposit`, `/nation withdraw` | — | **MISSING** — the nation account exists and is credited; nothing can spend it |
| `/nation set spawn`, `/nation spawn` | — | **MISSING** |
| `/nation list` | `/nation list [page] [order]` | DONE |
| `/nation online` | — | **MISSING** |
| `/nation set board`, `/nation set tag` | `/nation set board\|tag\|colour\|neutral` | DONE |

## 4. Plots

| Towny behaviour | RiftTowny | Status |
|---|---|---|
| `/plot claim` — a resident takes a plot, at a price | `/plot claim`, `prices.plot` paid to the town | DONE |
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
| `/towny map` | `/town map [small\|big]` | DONE — colour says whose (yours, your nation's, elsewhere, ruins, nobody's), shape says what (home, outpost, your plot, claimed). Hover names the town, click opens it, and the text is complete without either, so it reads the same through Geyser |
| Outposts | `/town claim outpost` | DONE |
| Claim contiguity | Breadth-first sweep from anchors | DONE |
| Ruined towns: unprotected, reclaimable, then deleted | Same | DONE |
| `/town reclaim` | `/town reclaim` | DONE — restores the town under its own name and id |
| Wilderness regeneration after unclaim | — | **MISSING** — `RT-MOD-REGEN` |
| War-time claim rules | — | **MISSING** — RiftWars |

## 6. Systems with no command surface yet

| Towny behaviour | RiftTowny | Status |
|---|---|---|
| Economy: town/nation banks, taxes, upkeep, plot prices | Town bank, ledger, RiftEco wallet, all six prices, and taxes with upkeep and bankruptcy | PARTIAL — no `/nation bank` command (the nation account is credited by tax and cannot yet be spent), no plot resale |
| Chat channels: town, nation | `/tc`, `/nc` — with a message, or bare to switch the channel on | DONE — RiftTowny picks the recipients, RiftChat renders. With RiftChat absent it renders a plain line itself rather than losing the channel |
| Ally chat | — | **MISSING** — blocked twice: `RiftChatService.Channel` has no `ALLY`, and `RT-MOD-DIPLOMACY` gives it nobody to reach. A `/ac` that accepted a message and delivered it to no one would be worse than none |
| `%townyadvanced_*%` placeholders | The whole 143-name manifest served under Towny's own identifier | PARTIAL — every name resolves and none can reach a player as raw markup; roughly seventy carry real values and the rest return their documented blank, each with a recorded reason. Balances wait on a snapshot cache; the level-derived numbers wait on `RT-MOD-PROGRESSION` |
| `%rel_townyadvanced_color%` | Served, as the viewed player's own allegiance colour | PARTIAL — ally / enemy / at-war need `RT-MOD-DIPLOMACY` |
| Dynmap/BlueMap/squaremap territory rendering | — | **MISSING** — `RT-MOD-MAP` |
| Jails, outlaws, courts | — | **MISSING** — `RT-MOD-JUSTICE` |
| Discord relay | — | **MISSING** — `RT-MOD-DISCORD` |
| `/townyadmin …` — the whole administrative surface | `/rifttowny status` only | PARTIAL |
| Importing an existing Towny database | `CivicImporter` plus `TownySqlSource`, 34 tests | PARTIAL — residents, towns, nations, claims, plots, homeblocks, boards and tags come across from Towny's MySQL database, with a dry run first. **Not yet run against a real Towny installation**, and no flatfile reader for servers that never moved to MySQL. See COMPATIBILITY_MATRIX §5 |

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

1. **Chat channels** (`RT-MOD-CHAT`) — `/tc` and `/nc`. Nothing upstream is in the way:
   `RiftChatService.Channel` already declares `TOWN` and `NATION`, and RiftTowny is the thing that
   knows who is in them. `/ac` is blocked twice over — no `ALLY` constant, and no allies to send to.
2. **Migration** (`RT-MOD-MIGRATE`) — no server can switch to RiftTowny without importing its Towny
   database.
3. **Justice and diplomacy** (`RT-MOD-JUSTICE`, `RT-MOD-DIPLOMACY`) — jails, outlaws, allies and
   enemies. The largest remaining block by row count, and the one with no dependency left in its way.
   Diplomacy also unblocks the relational colour and four location placeholders.
4. **The GUI** (`RT-CORE-UI`) — nothing declares `Surface.GUI` yet, which is what the parity test
   wants to see while there is no menu, and will stop being true the moment there is one.

Economy, the read-only surface and the placeholder surface have all come off this list. The
placeholder one mattered most for switching: a server's scoreboards, Discord embeds and web panels
are written against `%townyadvanced_*%`, and until an hour ago every one of them would have gone
blank on the day it changed plugin.

A server could not replace Towny with RiftTowny today. It could run a new world on it.
