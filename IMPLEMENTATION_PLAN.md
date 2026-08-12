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
- [~] **`RT-CORE-FLAGS` listeners** — break, place, multi-block place, buckets, interact, entity interact, armour stands, entity and vehicle damage, hangings, explosions, fire, ignition, fluid flow across a border, pistons reaching into foreign land. `rifttowny.bypass` skips all of it. **Not runtime-verified: no Paper server here**, and the pieces that need the Bukkit registry (`BlockActions`, `Chunks.of`) are covered by compilation and review rather than by tests
- [x] **Flag persistence** — `V4` adds `rt_flag_override`, keyed on `(scope, target, flag, relationship)` with the target encoded as one canonical string so the uniqueness constraint means the same thing on both dialects. `FlagOverrides` holds them in memory as built layers; `FlagService` gates a change on `MANAGE_FLAGS` **and** on the town actually owning the target, because the resolver reads a claim's overrides without asking who wrote them. `/town flag set|clear|here|list`. Disbanding sweeps a town's overrides and its claims', since the target column cannot carry a foreign key
- [~] **Per-block history and rollback** — **upstream done**: implemented in `Riftlogger@4e91300` (schema 3, batched writes, area queries, retention, rollback planning, 14 tests). RiftTowny's adapter and listeners are **not** written, so `AUDIT_BLOCK_HISTORY` still reports `BLOCKED` and nothing is being recorded

- [x] **`RT-CORE-NATION`** — `NationService`: found, invite, join, leave, expel, move capital, transfer the crown, rename, disband. Standing is indirect (citizenship is residency in a member town), so every check resolves the actor's town first. `/nation` wired
- [x] **Two-sided joining** — `V5` adds `rt_invitation`. Neither one-sided rule was safe: a nation that could admit a town would move its protection relationship without asking, and a town that could attach itself to any nation would walk into every member town's territory as a citizen. The nation offers, the town's own leadership accepts, and the accept consumes the offer in the same transaction as the join. Lapsed offers are hidden from listings, refused on accept, and swept hourly

### Next
- [ ] **`NationRoleService`** — nation roles can be created at founding and read, but not edited: a nation's leader holds everything and its citizens hold the member defaults. The three escalation guards are identical to the town service's and want extracting rather than copying
- [ ] **Protection gaps to close** — no border-crossing message, no area or plot overrides (`AREA` is a declared layer with no source), no operator command for the world and admin layers (the service supports them; only `/town flag` is wired), and `BlockActions` covers interactive blocks by name so an unlisted one is treated as scenery
- [ ] `RT-CORE-UI` — Java GUI framework and Floodgate Bedrock forms. **Nothing declares `Surface.GUI` yet**, which is why the parity test passes; it becomes a real constraint with the first menu
- [ ] `NationService` and nation roles — a nation's standing depends on residency in a member town, which is a different lookup than a town's
- [ ] 3D areas with non-overlap enforcement, plots, districts
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
- [ ] Ruins and land regeneration
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
