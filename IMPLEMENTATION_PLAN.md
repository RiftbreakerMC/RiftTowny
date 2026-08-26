# RiftTowny — Implementation Plan and Checklist

**Legend.** `[x]` implemented **and** covered by a passing test. `[~]` partially
implemented — the gap is stated on the line. `[ ]` planned, no code. `[!]` blocked on
something outside this repository, with the blocker named.

A line is only marked `[x]` when `mvn clean verify` passes with it and it has no hidden
TODO. "Compiles" is not "implemented".

Last updated: 2026-08-09. Current state: **Phase 1 foundation complete and verified**
(`mvn clean verify` green on Temurin 25.0.3, 53 tests, shipped jar probed). Phase 2 not started.

---

## Ecosystem split (2026-08-10)

The project is now five plugins, not one. Release order is fixed:

`RiftTowny → RiftSeasons → RiftWars → RiftInfrastructure → RiftCivics`

- [MODULE_GRAPH.md](MODULE_GRAPH.md) — plugins, acyclic dependency direction, the nine
  capability interfaces, RiftTowny's internal module register
- [RELEASE_ERAS.md](RELEASE_ERAS.md) — release order vs server eras, and where the original
  six phases went
- [FEATURE_CATALOG.md](FEATURE_CATALOG.md) — every feature with a stable ID and a status
- [RIFTWARS_SPECIFICATION.md](RIFTWARS_SPECIFICATION.md) — profile engine and the Siege
  profile, including how the approved war decisions map onto it
- [RIFTWARS_SEASONS.md](RIFTWARS_SEASONS.md) — season lifecycle
- [SECURITY_AND_PRIVACY.md](SECURITY_AND_PRIVACY.md) — espionage vs SocialSpy, exclusions

**What this changes for the work in progress: nothing.** Phase 2 is RiftTowny core, which
is release 1 in the new order. War moves out of RiftTowny into RiftWars, released third —
old Phase 4's war content is now RiftWars: Frontier, and old Phase 4's flight, Soft
Landing, bounty and supply-drop content stays in RiftTowny.

## Decisions taken (2026-08-09)

| Decision | Choice | Why |
|---|---|---|
| Build system | Maven multi-module | All 16 sibling repos are Maven with one shared Jenkinsfile, GitHub Packages publishing and shade conventions. Gradle would have made RiftTowny the only outlier and required re-solving the shade/relocation traps. Overrides the brief's Gradle Kotlin DSL. |
| Coordinates | `net.riftbreaker` / `rifttowny-*` / `net.riftbreaker.rifttowny.*` | Matches RiftCore and RiftEco, the two plugins RiftTowny couples to hardest. artifactIds lowercase because GitHub Packages rejects uppercase with HTTP 422. Overrides the brief's `com.riftbreaker.rifttowny`. |
| Towny bridges in siblings | Native `rifttowny-api` adapters, one PR per sibling | 54 files across 12 sibling repos bridge to Towny by reflection today. A `com.palmergames` shim would keep them working untouched but squats another project's namespace and carries clean-room risk. |
| Paper version | Pinned `26.2.build.92-stable` | A `[26.2.build,)` range re-resolves every build and silently moves the compile target. |

---

## Phase 1 — Foundation

Goal: a jar that starts on Paper and Folia, connects to either database, exposes an empty
but real public API, and fails loudly instead of silently when something is wrong.

### 1.1 Build and repository
- [x] Maven parent + five modules (`api`, `domain`, `storage`, `integrations`, `paper`)
- [x] Java 25, `-Xlint:all -Werror`, UTF-8, pinned paper-api
- [x] `.gitattributes` pinning sources to LF (CRLF breaks source-matching tests and is a known org-wide trap)
- [x] `Jenkinsfile` — temurin-25/maven-3, build, Packages deploy with 409 tolerance, opt-in release
- [x] GitHub Packages `distributionManagement`
- [x] Shade configuration: HikariCP relocated, `org.sqlite` **never** relocated

### 1.2 Scheduler abstraction
- [x] `RiftScheduler` API: global, region-by-position, entity, async, delayed, repeating, `supplyRegion`
- [x] Folia detection at bootstrap (`io.papermc.paper.threadedregions.RegionizedServer`)
- [x] `RiftTask` cancellation contract, uniform across both platforms
- [x] Shared translation layer (`BackedRiftScheduler`) — duration→tick conversion, `>> 4` chunk derivation for negative coordinates, argument validation, exception propagation through `supplyRegion`. 13 tests against a recording backend
- [~] Paper backend — written and compiles; **not runtime-verified**, no Paper server in this workspace
- [~] Folia backend — written against the real `io.papermc.paper.threadedregions.scheduler` API read from the pinned paper-api jar (`RegionScheduler`, `EntityScheduler`, `AsyncScheduler`, `GlobalRegionScheduler`, `ScheduledTask`); **not runtime-verified**, see R-07

### 1.3 Configuration and messages
- [x] Typed configuration (`RiftTownySettings`) building the JDBC URL rather than accepting a hand-written one
- [x] MiniMessage message service; every user-facing string externalised to `messages.yml`
- [x] Each key carries a built-in default, so a missing or broken template degrades to shipped wording instead of a blank line
- [x] Untrusted values inserted with `Placeholder.unparsed`, so a town name cannot inject MiniMessage tags
- [x] Tests: every key present in the bundled file, every default and every bundled template parses
- [ ] Per-locale message bundles

### 1.4 Storage
- [x] Dialect abstraction (MariaDB, SQLite) over one schema, two parallel migration sets
- [x] HikariCP pooling, relocated; SQLite pool floor of 4 so the second connection a repository opens while holding the first cannot deadlock
- [x] SQLite: WAL, foreign keys, busy timeout, application-level write serialisation
- [x] **SQLite refuses network/shared mode** — startup aborts rather than warns
- [x] Flyway given the plugin's own classloader, and a guard that turns "no migrations found" into a startup error instead of an empty schema
- [x] `V1` baseline: residents, towns, nations, claims, areas, roles, role permissions, role members, organisation currencies, outbox, idempotency, audit
- [x] Persistent transactional outbox — append-idempotent, network-exclusive claim, stale-claim reclaim, retry budget then park, prune, depth counts (9 tests)
- [x] Idempotency store — claim/complete/release/prune, with release refusing to undo a completed operation (5 tests)
- [~] Repository interfaces — outbox and idempotency exist and are implemented. Resident, town, nation, claim, area and role tables exist in the schema but **have no repositories yet**; those land in Phase 2 with the entities they serve
- [ ] Offline SQLite ⇄ MariaDB migration tool with validation and backup
- [ ] Backup and integrity-check commands
- [!] MariaDB runtime verification — **no MariaDB in this workspace**; the MariaDB migration is written and reviewed but has never been applied. See R-07

### 1.5 Public API
- [x] `RiftTownyApi` entry point, `RiftTownyProvider` registration, `ApiVersion` negotiation with a failure message naming both versions
- [x] Capability discovery: `Capability`, `CapabilityState`, `CapabilityStatus`, `CapabilityRegistry`
- [x] Bukkit-free value types: `WorldPosition`, `ChunkKey`
- [ ] Read-only views (resident, town, nation, claim, area, role) — Phase 2, with the entities they view
- [ ] Typed pre/post events — Phase 2, for the same reason

### 1.6 Integration capability registry
- [x] Registry with per-integration state: `ABSENT`, `PRESENT_UNVERIFIED`, `ACTIVE`, `FAILED`, `BLOCKED`, `DISABLED`
- [x] `LinkageError` caught deliberately — a `NoSuchMethodError` from a version mismatch degrades one feature instead of aborting the enable
- [x] Never over-reports: a plugin that is present but hands back no service is `PRESENT_UNVERIFIED`, never `ACTIVE`
- [x] `DISCORD_CHANNEL_PROVISIONING` registered `BLOCKED` at startup with the reason, rather than silently absent
- [x] Registry itself is Bukkit-free (presence arrives as a predicate), so degradation behaviour is tested — 10 tests
- [x] `/rifttowny status` reports the registry's real state; the outbox depth is fetched asynchronously so the command never blocks a server thread
- [ ] The adapters themselves — Phase 2+, per INTEGRATION_CONTRACTS.md

### 1.7 Tests and CI
- [x] `mvn clean verify` green on Temurin 25.0.3 with `-Xlint:all -Werror`
- [x] Architecture tests: `domain` and `storage` import no `org.bukkit`, `io.papermc.paper` or `net.kyori.adventure`
- [x] Storage tests against real SQLite: migration applies, re-applies as a no-op, and chunk uniqueness is enforced by the schema
- [x] Claim expiry tested against an injected clock rather than a sleep, so it cannot flake on a loaded CI agent
- [x] **Shipped-jar probe**: `java -cp RiftTowny.jar ShadedJarProbe.java` opens the relocated Hikari pool, loads the unrelocated sqlite-jdbc JNI driver, migrates through the jar's own classloader and round-trips the outbox. This is the check that would have caught the `org.sqlite` relocation incident, and it only fires from a shaded jar on the SQLite backend
- [x] Jar inspection: 0 `com/zaxxer` entries, 187 unrelocated `org/sqlite`, 88 relocated hikari, both migration sets present, `java.sql.Driver` service entry survived shading
- [ ] Folia runtime smoke test — **needs a Folia 26.2 server**; see R-07
- [ ] Jenkins first green run — pipeline written, never executed

---

## Phase 2 — Core town replacement  ·  release **RiftTowny: Founding**

Feature IDs in [FEATURE_CATALOG.md](FEATURE_CATALOG.md) §1.

### Done and tested

- [x] Domain style: object-oriented aggregates. `Resident`, `Town` and `Nation` are immutable objects owning their own invariants; every change returns a new instance inside a sealed `Outcome` carrying the events it produced, so an event cannot exist for a change that did not happen
- [x] Typed identity — `ResidentId`, `TownId`, `NationId`, sealed `OrganisationId`
- [x] Name validation — display, normalised uniqueness key and lookalike skeleton from one pass; the `i`/`l`/`1` class folds together
- [x] Membership invariants — one town per resident, one nation per town, trust grants nothing, last-resident and mayor-ordering rules, capital rules
- [x] `ResidentRepository`, `TownRepository`, `NationRepository` + JDBC implementations, tested on real SQLite; `V2` adds `rt_town_trust`
- [x] **Unit of work** — `CivicStore.inTransaction` gives a service synchronous resident/town/nation stores plus `publish()`. State change and outbox rows commit together or not at all, tested in both directions. SQL lives in connection-scoped stores that both the async repositories and a transaction run, so there is one copy
- [x] `TownService` — found, join, leave, kick, transfer mayoralty, rename, disband. Name uniqueness checked inside the transaction; refusals travel as `ChangeRefusedException` and come back as a `ServiceResult`
- [x] **`RT-CORE-ROLE` domain and storage** — `Permission` catalogue split action/management with admin-lockable sensitive entries; `Role` and `RoleBook` aggregates; three undeletable system roles; priority-based management; union-for-permissions, maximum-for-rank resolution; `V3` adds `system_type`
- [x] **Authority at the service boundary** — `TownService` resolves the actor against the town's role book before touching anything, because the public API reaches the same methods
- [x] `TownRoleService` — create, clone, rename, reprioritise, grant, revoke, assign, unassign, with the three escalation guards (outrank the role, hold what you grant, never create above yourself)
- [x] `RiftTownyPlugin.getInstance()` — the plugin is reached through the main class rather than threaded through constructors

- [x] **`RT-CORE-CMD`** — command tree, router and completion from one structure; `Surface` declared per action so "no feature is GUI-only" is checkable; `/town` and `/town role` wired to the services. **Not runtime-verified: no Paper server here**
- [x] Refusal text keyed by enum constant, with a test asserting every `ChangeDenial` and `NameProblem` has a sentence
- [x] **`RT-CORE-CLAIM`** — claim kinds, homeblock, outposts, orthogonal contiguity by breadth-first sweep from anchors, severing refused, preview sharing the real rules; `rt_claim` storage written one chunk at a time; `TerritoryService` with cross-town ownership checked inside the transaction; `/town claim|unclaim|preview|homeblock`
- [x] **Nine review defects fixed**, two of them authority escalations: a departed resident kept their roles, and `assign` was the one permission path not bounded by what the actor holds
- [x] **CoreProtect dropped** (2026-08-10) — RiftLogger is the only audit integration
- [x] **`RT-CORE-FLAGS` resolver** — flag catalogue, relationship ladder, seven-layer ordered resolution, world flags collapsing to wilderness, decisions that explain which layer decided; pure and exhaustively tested
- [x] **In-memory answering** — `TerritoryIndex` holds every claim, `CivicCache` holds every town's residents, trust and role book. `CivicCacheService` fills both at enable and re-reads a town after every committed change, so protection never touches storage and never answers from stale membership
- [x] **`ProtectionQuery`** — one synchronous call composing territory, relationship, flags and the member's role permission. Two gates: the territory decides what a relationship may do, the role decides what a person may do, both must pass. An unknown town denies rather than falling through to allowed
- [~] **`RT-CORE-FLAGS` listeners** — break, place, multi-block place, buckets, interact, entity interact, armour stands, entity and vehicle damage, hangings, explosions, fire, ignition, fluid flow across a border, pistons reaching into foreign land. `rifttowny.bypass` skips all of it. **Not runtime-verified: no Paper server here.** `BlockActions` is now tested: `BlockTags` is the seam that made it possible, because `org.bukkit.Tag`'s constants come from a running server's registry and threw in a bare JVM, which is precisely why the class had no test and why a hundred and nineteen materials went unrecognised in it. What a substitute cannot prove is tag *membership* — that is server data, absent from the API jar — so that half is one verified line per tag in `BukkitBlockTags` and nothing else. `Chunks.of` is still compilation and review only
- [x] **Flag persistence** — `V4` adds `rt_flag_override`, keyed on `(scope, target, flag, relationship)` with the target encoded as one canonical string so the uniqueness constraint means the same thing on both dialects. `FlagOverrides` holds them in memory as built layers; `FlagService` gates a change on `MANAGE_FLAGS` **and** on the town actually owning the target, because the resolver reads a claim's overrides without asking who wrote them. `/town flag set|clear|here|list`. Disbanding sweeps a town's overrides and its claims', since the target column cannot carry a foreign key
- [~] **Per-block history and rollback** — **upstream done**: implemented in `Riftlogger@4e91300` (schema 3, batched writes, area queries, retention, rollback planning, 14 tests). RiftTowny's adapter and listeners are **not** written, so `AUDIT_BLOCK_HISTORY` still reports `BLOCKED` and nothing is being recorded

- [x] **`RT-CORE-NATION`** — `NationService`: found, invite, join, leave, expel, move capital, transfer the crown, rename, disband. Standing is indirect (citizenship is residency in a member town), so every check resolves the actor's town first. `/nation` wired
- [x] **[TOWNY_PARITY.md](TOWNY_PARITY.md)** — the behaviour-parity target, row by row, with what is DONE, PARTIAL, MISSING and deliberately DIFFERENT. Written because "exactly like Towny" is not checkable and a list is. It also draws the line the clean-room rule requires: observable behaviour, command shapes and the placeholder surface are the target; code, message text, configuration keys and internal design are not
- [x] **Resident names** — everything is stored as a UUID because a name is not an identity, which left `/town info` printing thirty-six characters of hex at the player. A cache bounded by town membership names them, refreshed on join, and `last_known_name` now follows a rename instead of lying
- [x] **Territory notices** — a line above the hotbar as a player crosses into a town, out into wilderness, into a ruin, or onto somebody's plot. Announced on a change of ground rather than a change of chunk, so walking through the middle of a town says nothing. Replaced the ruin-only notice, which was the same listener with one case
- [x] **Plots** — `V9` puts the plot on `rt_claim` rather than in a table beside it, because "does this player hold the plot they are standing on" is asked on every block a player touches and belongs in the row that already answers which town owns the chunk. `/plot info|claim|unclaim|set|list`, ten plot types, and the release of a departing resident's plots. **This is what makes `Relationship.RESIDENT` reachable** — the rung has been in the ladder since it was written and, until now, nothing could ever satisfy it
- [x] **Spawn warmup and cooldown** — `/town spawn` waits, in the open, and is cancelled by moving a whole block or by being hit. That is what stops it being an escape from every fight on the server, and it needs no combat-tag integration to do it. Both configurable; `RiftScheduler.entityDelayed` was added for the warmup, because an async delay followed by a hop leaves a window on Folia in which the player changed region
- [x] **Town spawns** — `V8` adds `rt_town_spawn`. `/town setspawn`, `/town spawn`, `/town delspawn`. A spawn must sit in the town's own land, and stops existing when that land does — checked at travel time as well as on unclaim, so a spawn can never drop a player into somebody else's territory. `TOWN_SPAWN` gates travelling in, `SET_SPAWN` gates moving it. The teleport goes through `Player#teleportAsync` from the player's own thread, which is the Folia-safe path. **Not runtime-verified**: no server here
- [x] **Joining a town takes the player's consent** — `/town add` sends an invitation; the player answers with `/town accept` or `/town deny`, and `/town invites` lists what they have been offered. Uses the same `rt_invitation` table the nation flow does, which is what it was made generic for. `TownService.join` survives as the forced path for administration and migration imports, documented as bypassing consent and reached by no command
- [x] **Two-sided joining** — `V5` adds `rt_invitation`. Neither one-sided rule was safe: a nation that could admit a town would move its protection relationship without asking, and a town that could attach itself to any nation would walk into every member town's territory as a citizen. The nation offers, the town's own leadership accepts, and the accept consumes the offer in the same transaction as the join. Lapsed offers are hidden from listings, refused on accept, and swept hourly

- [x] **`NationRoleService`** — the three escalation guards were **extracted** into a shared `RoleEditor` rather than copied, because a copy is how one of them quietly stops matching the other and only the weaker one is ever tested. `TownRoleService` and `NationRoleService` are typed faces over it, so a nation id cannot reach a method meaning a town's. All that genuinely differs is how standing and membership are answered: a nation has no residents, so both need the actor's town first. `/nation role` wired

- [x] **Ruins** — `V6` adds `rt_ruin` and `rt_ruin_claim`, `V7` the reclaim window and homeblock. A disbanded town's land does not become wilderness immediately: it becomes a ruin for a configured window, held by `RuinIndex` beside the territory and civic caches. **A ruin is deliberately unprotected** — it is looted, burned, blown up and fought over — and after a configured delay anybody townless may rebuild it with `/town reclaim`, which restores the town under its own name and its own id. `LandState` replaced the `claimed` boolean, because a ruin is neither claimed nor unclaimed. Lapsed ruins are swept on a schedule; the row survives the sweep, since `RT-MOD-REGEN` and the anti-recreation rule both read it. Behaviour matched to Towny's ruined towns; **no code, message text, configuration or internal design taken from it**

### Next
- [ ] **Ruins are not yet an outcome of anything but disband** — a town that falls to bankruptcy, inactivity or a siege should leave one too. Those systems do not exist; when they do, they call the same `RuinService.recordFall`
- [ ] **No reclaim price** — Towny charges for taking a ruin on, and that is the lever that stops a wealthy player hoovering up every fallen town. It needs `RT-MOD-BANK`, which is unbuilt, so reclaiming is free and the only cost is the delay
- [ ] **Spawn travel has no cost, and no public or nation destinations** — the cost wants `RT-MOD-BANK`; only your own town's spawn is reachable. The combat hole is closed by the warmup rather than by a combat tag, so a player who is *about* to be attacked can still leave; a RiftPvP tag would close that too
- [x] **Nation roles no longer outlive citizenship** — the fix had to land in four places, because a nation's citizens are residents of its member towns and there is no single event that ends that: a resident leaves or is kicked, their town leaves or is expelled, or their town is disbanded. `CitizenRoles.revoke` gathers it so the fifth caller added later inherits it. Coming back does not restore the role — it was granted personally, and re-granting is the nation's decision
- [~] **Protection gaps to close** — the block-side holes are closed; two remain. **Done**: families are matched by vanilla tag rather than by name, so signs (all 48), shelves, copper chests, beehives, cave vines, flower pots and campfires are covered and a wood type added in a later release arrives already protected. Crop harvest (berries, glow-berry vines) answers `FARMLAND`, which `Permission.FARMLAND` is already documented as covering ("trampling farmland, harvesting crops"), rather than `CONTAINER` — otherwise "may pick berries" and "may open every chest in town" are one grant. An end portal frame is a `BUILD`, because an inserted eye cannot be taken out again. A spawner now answers `SPAWNER_USE` rather than the switch permission — that flag had existed since the ladder was written and nothing in the Paper module had ever reached it. A dragon egg is caught on both the punch and the use that teleport it, since neither fires a break event. The tool in hand is tested **before** the block's own meaning, because where a pair matches the tool is what the game does: an axe on a copper door scrapes it rather than opening it, and judging the door first would have let anybody who may open a gate permanently restyle the town's copper. `EntityPlaceEvent` and `HangingPlaceEvent` are listened to, so hanging a painting or setting down a boat inside a claim is a `BUILD` again rather than a hole beside the wall. And the tool in hand is judged with the block: stripping a log, scraping or waxing copper, pathing grass, tilling dirt and carving a pumpkin all rewrite a town's blocks with no break or place event, and none of them could ever have been caught by a list of materials. Bone meal is the one member of that family a `(block, tool)` pair cannot express — what it does depends on what is under it — so it goes through `BlockFertilizeEvent`, which fires only when the fertilising took effect and is therefore both narrower and more accurate than any list. `/rifttowny flag <set|clear|list> <server|world <name>>` is what finally makes the ladder seven layers rather than five: `ADMIN` and `WORLD` were resolved on every protection check and no surface could put a row in either — `setAdministrative` was reached by one test, `clearAdministrative` by nothing at all. Its own permission node rather than a child of the admin parent, because "may forbid something server-wide, over every town's own setting" is not the same decision as "may read diagnostics". **Still open**: no area overrides — `AREA` is a declared layer with no source and needs `RT-CORE-AREA`
- [x] **`RT-MOD-BANK`** — `V10` adds `rt_organisation_balance` and `rt_bank_ledger`. `Money` is `BigDecimal` because a ledger of doubles loses money; the ledger is ordered by a sequence rather than a timestamp because two movements in the same millisecond are ordinary. `/town bank`, `/town deposit`, `/town withdraw`, and a treasury line on `/town info`
- [x] **RiftEco bound for real** — cloned, installed locally, and the adapter compiled against the actual API (`provided` scope; a test asserts no `net/riftbreaker/eco/` class is shaded into the jar). Only the player wallet is used: RiftEco's own town accounts and Towny bridge are deliberately untouched, because a treasury in another plugin's storage is outside our transaction. Without RiftEco, `PlayerWallet.absent()` refuses transfers and the civic ledger is unaffected. [INTEGRATION_CONTRACTS.md](INTEGRATION_CONTRACTS.md) §2.7 is now `VERIFIED`
- [x] **Nation treasuries can be spent** — nations had been paid by the tax run since it was written and had no way to show or spend it, so the money accumulated where nobody could see it: indistinguishable, from outside, from a nation tax that was quietly not working. `/nation bank`, `/nation bank deposit|withdraw`, and a treasury line on `/nation info`. Nested under `bank` rather than flat because `/nation withdraw` already means withdrawing an invitation, and two meanings on one word is how somebody cancelling an offer empties the treasury instead. `BankService` resolves a **holder** rather than a town id, so one credit and one debit serve both kinds of treasury — the alternative was a second copy of the money-moving code, which is the last place two implementations should be allowed to drift. The nation permission check moved into a shared `CivicPermissions` on the way, because there were already two copies of it and they had already diverged: `NationService` refused a nation with no role book as `ROLE_NOT_FOUND` and `DiplomacyService` refused the same state as `MISSING_PERMISSION`
- [x] **Prices** — all six charged, all zero by default. Claiming and its refund move money inside the treasury, in the same transaction as the claim, so a town can never own a chunk it did not pay for. Founding, plots, reclaim and spawn travel come from the player's own wallet through `PlayerCharge`, which takes the money first and gives it back if the civic half refuses — solved once so four paths cannot solve it four subtly different ways. Where the money goes is a decision per price: a plot fee and a spawn fare go to the town, a founding fee and a reclaim fee leave the economy, because a fee that funded the thing it paid for would be no fee at all. The spawn fare is taken on arrival, so a warmup cancelled by a punch costs nothing

### Next, in the order [TOWNY_PARITY.md](TOWNY_PARITY.md) argues for
- [x] **`RT-MOD-TAX`** — `V11` adds `rt_tax_run` and `rt_town.unpaid_since`. Resident tax, per-chunk upkeep and nation tax in one run, in that order: residents pay their town before the town is judged on what it holds. Idempotent by a period key claimed on a primary key, so several backend servers on one database run it once between them. A town that cannot pay is marked and given the configured grace, and only then falls — **through the same disband path**, so bankruptcy leaves a ruin like any other fall and is the first source of one nobody chose. Off by default. **Deliberately unlike Towny**: a resident who cannot pay is not evicted
- [x] **The read-only surface** — `/resident [player]`, `/resident list`, `/town info [town]`, `/town list`, `/town online`, `/town map`, `/nation info [nation]`, `/nation list`, `/nation online`. No new table and no new query on the hot paths: the listings are a read model (`CivicDirectory`, `TerritoryMap`) over the caches protection already reads, so looking at a town costs nothing and a curious player cannot stall a server. The two things a cache cannot answer — a treasury balance and a nation's *name* rather than its id — are read once for the whole screen and rendered in their callback, so the lines never arrive in a different order on a slower database. `/resident list` and `/nation online` are the same shape: `/resident list` is answered from the membership index protection already maintains, and lists residents **of towns** — somebody who has never joined one has a database row and no place in a directory whose cache is bounded by town membership, which the javadoc says rather than leaving to be discovered. `/nation online` labels each player with their member town, because a nation is towns rather than people and forty online in one town is a different situation from forty across eight. The map draws two channels: **colour says whose**, **shape says what**, and it stays readable through Geyser because neither hover nor click carries information the text does not
- [x] **Town and nation profiles** — `V12` adds board, tag, map colour and neutrality to both, plus openness and a public spawn to towns, plus title and surname columns to residents. Three kinds of thing, labelled as such rather than left to be discovered: presentation, a *declared* stance nothing enforces, and two settings with real behaviour. `open` is what makes `/town join` work without an invitation; `public` is what makes `/town spawn <town>` work for an outsider — and neither is a role bypass, so a member whose role cannot teleport is still refused on the public path
- [x] **`RT-MOD-PAPI`** — the whole 143-name `%townyadvanced_*%` manifest, under Towny's own identifier, answered from memory. The rule the whole design turns on: PlaceholderAPI reads `null` as "not mine" and leaves `%townyadvanced_whatever%` on screen, so **every manifest name returns a string**. A golden test sweeps all of them against a resident, a townless player and no player at all. Roughly seventy carry real values; the rest return a documented blank with the reason recorded beside it. Location reads `LastKnownChunk`, written by the movement listener, because resolving a position on an arbitrary thread is illegal on Folia
- [x] **A nation cache** — the last read model. Removed three queries (`/nation list`, `/town list`, `/town info`) and is what lets twenty nation placeholders answer at all. Refresh is driven off what each service method returns rather than a nation id threaded through nine call sites, because the ninth — the disband — is the one that would be forgotten
- [x] **`RT-MOD-CHAT`** — `/tc` and `/nc`, each sending one message or toggling the channel when given none. RiftTowny picks the recipients from the same cache protection reads, RiftChat renders. The toggled path **narrows `AsyncChatEvent.viewers()` rather than cancelling**: cancelling and re-sending would drop Paper's signed-message handling and fire nothing other chat plugins listen for, so their moderation and logging would silently stop applying to anything said in a town. It runs at `HIGHEST`, after RiftChat's `HIGH`, so RiftChat's formatting, emoji and link passes survive and only its hard-coded `GLOBAL` renderer is replaced. `/ac` stays unbuilt: no `ALLY` constant and no allies to reach
- [~] **`RT-MOD-MIGRATE`** — importer, a reader for Towny’s MySQL database, and `/rifttowny migrate towny [confirm]`; 40 tests. **Not yet run against a real Towny installation**, and no flatfile reader yet. The schema was read out of Towny's own artifact rather than guessed — two mappings would have been wrong if assumed: a nation has no leader column (the king is the capital's mayor), and a town's homeblock lives on the town as a `world,x,z` string rather than being flagged on the townblock. The reader takes `SELECT *` and reads by result-set metadata, so a column a different Towny version lacks reads as absent instead of failing the whole migration. The finding that shapes the module: RiftTowny disables itself when Towny is present, so the two are never running together and no importer can read Towny through its API — every source must be offline. `CivicImporter` never overwrites an existing town, is idempotent so a failed run can simply be re-run, commits per town rather than once, and orders residents → towns → nations → claims because the aggregates refuse anything else. A dry run is the default and reports identical numbers to the real thing by construction — the counting lives in the pure validation pass, which a test caught after the first version counted while writing and previewed "0 towns"
- [ ] `RT-CORE-UI` — Java GUI framework and Floodgate Bedrock forms. **Nothing declares `Surface.GUI` yet**, which is why the parity test passes; it becomes a real constraint with the first menu
- [x] **`RT-MOD-DIPLOMACY`** — `V13`, declarations stored one way so an alliance needs both nations and an enmity needs one, the ALLY rung in protection reading it, `/nation relations ally|enemy|neutral|list`, and the ally/enemy/relational placeholders answering from the book. **Outstanding**: `/ac` still has no channel constant in RiftChat, so ally chat stays unbuilt rather than shipping a command that reaches nobody
- [ ] 3D areas with non-overlap enforcement, districts. Plots are done; areas are the sub-chunk shape and are still unbuilt
- [x] **`/town purge`** — removes residents nobody has seen for N days. Safe to build because `last_seen` is genuinely maintained: the join listener stamps it through `ResidentNameService.seen`, so it is a real last-login rather than a field written once at creation. A preview unless `confirm` is typed, and both numbers come from the same pass by construction - the rule the importer arrived at after previewing "0 towns" and importing forty. Two exclusions, both skipped rather than refused: the mayor, and anybody the actor does not outrank
- [x] **`/town unclaim all`** — keeps the homeblock, releases the rest. Deliberately **not** a loop over `unclaim`: a single unclaim refuses anything that would disconnect the remaining land, so a corridor town gets stuck on the first chunk that is not an end. Going straight to the end state never asks the question, because one chunk is connected by definition. Fixed a leak on the way: neither unclaim path cleared the released chunk's own flag overrides, and an override is keyed on the chunk rather than on its owner - so one left behind came back into force against whoever claimed that chunk next, a rule the new owner never set and could not see. A mutation test pins it
- [x] **`/town merge`** — two towns becoming one, and the ordering inside it is the whole feature: `rt_claim` cascades from `rt_town` and `ConnectionTownStore.delete` nulls `rt_resident.town_id`, so the land has to change hands and the people have to move **before** the absorbed row goes. Written the other way round it destroys the land it was moving and strands the people, with no exception and a success message - a mutation test proves the land test catches exactly that. One transaction, bounded by residents rather than by land because the claims move in a single `UPDATE` through the new `ClaimStore.reassignAllOf`. Money moves as a debit and a credit so both ledgers show where it went, and `BankStore.forget` is never called because it would delete the ledger with the balance. Refused across nations, since allowing it would either enrol strangers in a nation that never invited them or take a member out of one on two town mayors' word
- [x] **`/town trust`** — the other half, and almost nothing had to be built: `rt_town_trust`, `Town.trust/untrust`, the `OutsiderTrusted/Untrusted` events, the three denials and the `TRUSTED` rung have all existed since `V2`, and the store already wrote the list beside the town row. What was missing was two service methods and a command, so for the whole of that time the rung could be resolved and no town could put a name in it
- [x] **`/town outlaw`** — `V14` adds `rt_town_outlaw`, the mirror of the trust table. `OUTLAW` is the lowest rung of the relationship ladder rather than a boolean beside it, so a town configures what an outlaw may do exactly as it does for a visitor, and no default had to change: `allowedByDefault` asks `isMember` and `isAtLeast`, and an outlaw fails both. Checked after membership and before every outsider rung — above it would let a town strip its own member, below it would let an outlaw in an allied nation keep the `ALLY` rung and walk in. Gated by `MANAGE_TRUST`, since the trust list and the outlaw list are one decision from opposite ends. **No physical ejection**: the brief excludes forced imprisonment, and an outlaw who can stand there and do nothing is the sanction without the coercion
- [ ] **Plot selling and renting** — `RT-MOD-PROPERTY`. A plot is currently taken by asking, first come first served, with no price and no limit on how many one resident may hold. That wants an economy (`RT-MOD-BANK`) before it is worth building
- [ ] **Plot types carry no behaviour** — a plot marked `SHOP`, `JAIL` or `BANK` is recorded and shown, and nothing reads it yet. `RT-MOD-SHOP`, `RT-MOD-JUSTICE` and `RT-MOD-BANK` are what give them meaning
- [ ] Claim and area preview / dry-run
- [ ] Command trees with complete tab completion
- [ ] Adventure clickable chat for every action
- [ ] Java inventory GUIs
- [ ] Floodgate Bedrock forms (parity test: no action is GUI-only)
- [ ] Configurable roles with priority-based management
- [ ] Civic banks, multi-currency, RiftEco adapter
- [ ] Core `%townyadvanced_*%` placeholders with golden-output tests

## Phase 3 — Civic systems

- [ ] Taxes, nation taxes, upkeep, bankruptcy
- [ ] Elections and governance
- [ ] Diplomacy: allies, enemies, embargoes, treaties
- [x] Ruins — done in Phase 2, because disbanding needed something to hand its land to. Regeneration (`RT-MOD-REGEN`) still outstanding: a lapsed ruin's land reverts to wilderness with its buildings intact
- [ ] Safe-location engine; wilderness respawn; protected destination zones
- [ ] Town and nation leaderboards

## Phase 4 — Combat and events

- [ ] War state machine — **unblocked**: [docs/war-decisions.md](docs/war-decisions.md) approved 2026-08-09, all twelve decisions as recommended
- [ ] War shields
- [ ] RiftPvP integration (combat tag → flight removal, bounce-back)
- [ ] RiftBoosters flight, community booster, Soft Landing
- [ ] RiftEvents integration and bounties
- [ ] Supply drops and map markers

## Phase 5 — Ecosystem integrations

- [ ] RiftChat channels (`/tc`, `/nc`, `/ac`)
- [ ] VelocitySrv outbox transport and Discord announcements
- [!] Town/nation Discord channel provisioning — **blocked: VelocitySrv has no channel-creation API**
- [ ] RiftShop, RiftSpawners, mcMMO
- [ ] Complete placeholder and command compatibility
- [ ] Cross-server hardening

## Phase 6 — Advanced optional modules

Laws and justice; treaties and vassalage; resources, industry and climate; public
projects; cultures and cosmetics; quests; achievements and seasons; property markets;
borders and visas; camps; war logistics; ruin salvage; civic history. None of these may
delay a stable core release.

---

## Standing acceptance gate

Applied at the end of every phase, not at the end of the project:

1. `mvn clean verify` passes on Java 25.
2. Unit and integration tests pass.
3. No synchronous database or network call on a server thread; no placeholder that blocks
   on storage.
4. Paper and Folia scheduling rules respected.
5. No TODO hidden inside anything marked `[x]`.
6. Command, permission, configuration, schema, API and migration docs updated.
7. Java and Bedrock users can perform the same actions.
8. Missing integrations degrade safely.
9. Monetary operations atomic and idempotent.
10. Restart and reconnect behaviour tested.
