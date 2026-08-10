# RiftTowny — Specification

Status of this document: **living**. Sections are marked `[SPEC]` (agreed behaviour),
`[DRAFT]` (proposed, not yet approved) or `[BLOCKED]` (cannot be specified until an
external contract is known). Nothing here implies the behaviour is implemented — see
[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for what actually exists.

---

## 1. What RiftTowny is

RiftTowny is a standalone town, nation, territory, economy, governance, war, events and
integration platform for Paper and Folia 26.1–26.2 on Java 25.

It is **clean-room**. It is inspired *behaviourally* by Towny, Lands and HuskTowns —
players who know those plugins should find the commands and vocabulary familiar — but it
shares no code, configuration, message text, assets or internal design with any of them.
Compatibility is reproduced from observable public behaviour only.

### 1.1 Mutual exclusion with Towny `[SPEC]`

RiftTowny **cannot run alongside Towny**. The command tree (`/town`, `/nation`, `/plot`,
`/townyadmin`, …) and the `%townyadvanced_*%` PlaceholderAPI namespace both collide.

The plugin detects Towny at enable time and refuses to start with a single, explicit
message rather than half-registering and leaving a server in an ambiguous state.

> **Ecosystem consequence.** Twelve sibling RiftbreakerMC plugins currently bridge to
> Towny's own API by reflection. Installing RiftTowny makes every one of those bridges go
> dormant. The agreed remedy is a native `rifttowny-api` adapter per sibling — see
> [INTEGRATION_CONTRACTS.md](INTEGRATION_CONTRACTS.md) §2.

---

## 2. Architecture

### 2.1 Modules

| Module | Bukkit? | Purpose |
|---|---|---|
| `rifttowny-api` | paper-api `provided` | The published, versioned contract. Read-only views, typed events, capability discovery, version negotiation. Consumed by sibling plugins. |
| `rifttowny-domain` | **no** | Entities, services, the flag resolver, governance and war rules. Pure Java so the rules that matter are unit-testable without a server. |
| `rifttowny-storage` | **no** | JDBC repositories, Flyway migrations, dialect abstraction, the transactional outbox. |
| `rifttowny-integrations` | paper-api `provided` | The capability registry and one adapter per optional dependency. Every adapter is isolated so a missing plugin cannot break an unrelated subsystem. |
| `rifttowny-paper` | yes | The plugin: bootstrap, scheduler implementations, commands, listeners, GUIs, Bedrock forms, PAPI expansion. Shaded, shippable jar. |

Dependency direction is strictly one-way: `paper` → `integrations` → `storage` → `domain`
→ `api`. `domain` and `storage` never import `org.bukkit`. This is enforced by an
architecture test, not by convention.

### 2.2 Threading `[SPEC]`

Paper and Folia have incompatible threading models. RiftTowny never calls a Bukkit
scheduler directly; everything goes through `RiftScheduler`, which has a Paper
implementation and a Folia implementation selected once at bootstrap by detecting
`io.papermc.paper.threadedregions.RegionizedServer`.

Rules, all non-negotiable:

- **No blocking I/O on any server thread.** Database and network calls return
  `CompletableFuture` and run on RiftTowny's own executor.
- **No Bukkit world or entity access off its owning region.** Reads of world state are
  scheduled onto the region owning that location (Folia) or the main thread (Paper).
- **No placeholder resolution touches storage.** PlaceholderAPI parses on the calling
  thread, frequently on the main thread, and must never block. Placeholders resolve from
  an immutable snapshot cache only.
- Long-running sweeps (regeneration, upkeep, leaderboard rollups) are chunked and
  rate-limited, and on Folia are dispatched per region.

### 2.3 Persistence `[SPEC]`

One schema, one migration set, two dialects.

- **MariaDB/MySQL** — production and any multi-server (Velocity network) install. Pooled
  through HikariCP, relocated into `net.riftbreaker.rifttowny.libs.hikari`. Row locking
  and transactions are real; cross-server singleton operations use advisory locks.
- **SQLite** — development and single-server only. WAL, foreign keys on, busy timeout,
  serialised writes. **Explicitly rejects shared/network mode**: if the configuration
  declares more than one backend server, RiftTowny refuses to start on SQLite rather than
  corrupting data quietly.
- `org.sqlite` is **never relocated**. sqlite-jdbc is JNI and its native binary exports
  symbols named for the Java package; a shaded rename produces `UnsatisfiedLinkError` on
  Linux and `NoClassDefFoundError` on Windows, and only on the SQLite backend, so a
  MySQL-backed test run never reveals it.
- Migrations are Flyway, versioned `V<n>__<description>.sql`, one directory per dialect
  where the DDL genuinely differs. Flyway is given the plugin's own classloader — the
  thread context classloader sees nothing under a plugin classloader.

### 2.4 Identity `[SPEC]`

Every domain object has a stable `UUID` that is assigned once and never changes. Names are
mutable labels. Renaming a town, transferring a mayoralty, or a nation changing capital
must never change an ID, and must never orphan a bank account.

---

## 3. Domain model

### 3.1 Membership `[SPEC]`

- A **Resident** is a player, identified by their Mojang UUID.
- A resident is a member of **exactly one** town, or none.
- A **Town** has many residents and belongs to **at most one** nation.
- A **Nation** has many towns.
- **External trust is not membership.** A trusted outsider gains only the specific
  permission flags granted. Trust never confers voting rights, tax liability, bank
  authority, nation membership, or inclusion in any resident count.

### 3.2 Territory `[SPEC]`

Territory is chunk-based. A **claim** is one 16×16 chunk in one world owned by one town.

Inside claims, towns may define **areas**: 3D cuboids that never overlap another area in
the same town. Areas carry their own flag overrides and their own role permission
overrides.

Claim kinds: homeblock (exactly one per town, the claim the town spawn lives in),
ordinary claim, outpost (a disconnected claim group), plot, farm, market, district
(a named grouping of claims), embassy (a claim inside town A owned by town B), and
public area.

Relationships, resolved for every protected action:
`wilderness < visitor < trusted < ally < nation < town < resident`.

### 3.3 Flag resolution `[SPEC]`

One central resolver answers every protection question. Resolution order, first match
wins:

```
admin restriction  ->  war/event override  ->  area override
                   ->  claim setting       ->  organisation setting
                   ->  world default       ->  server default
```

Admin restrictions always win. An organisation can never grant itself something an
administrator has forbidden.

Flags cover: build, break, interact, containers, redstone, entity damage, mob spawning,
PvP, explosions, fluid flow, fire, pistons, shops, spawners, flight, event actions and
war actions.

Every claim and area operation supports **preview** and **dry-run**: the player sees what
would change, including cost and conflicts, before anything is written.

### 3.4 Roles `[SPEC]`

Towns and nations both carry a configurable role set, in the style of Lands.

- Roles may be created, cloned, renamed, reordered and deleted.
- Each role has a display name, icon, chat prefix, integer priority and a permission set.
- **A role may only manage roles of strictly lower priority.** This is enforced in the
  service layer, not only in commands.
- Three roles per organisation are immutable system roles: leader (mayor / king),
  member (resident) and visitor. They may be renamed and re-decorated but not deleted,
  and the leader role's authority cannot be revoked.
- Permissions split into **action** permissions (may I break a block here) and
  **management** permissions (may I change who can break blocks here). They are granted
  independently.
- Roles are exposed through commands, GUI, Bedrock forms, the public API, PlaceholderAPI
  and LuckPerms contexts, and every change emits an audit event.

---

## 4. Economy `[SPEC]`

RiftTowny does **not** implement a player economy. It consumes one.

- **Preferred provider: RiftEco**, through its typed asynchronous service API.
- **Fallback: VaultUnlocked**, used only when RiftEco is absent.
- If neither is present, every economic subsystem degrades to disabled — it does not
  fabricate balances and does not prevent territory or governance from working.

Every town and nation gets a stable civic account keyed by the organisation's UUID, which
survives renames and leadership changes.

### 4.1 Multiple currencies `[SPEC]`

RiftEco supports multiple currencies. RiftTowny stores **stable currency IDs**, never
display names.

Resolution order for any transaction:

```
admin-forced currency -> transaction currency -> organisation default -> server default
```

- Administrators set the server default and an allowlist.
- Organisations pick their own default from the allowlist, where permitted.
- A civic bank holds an independent balance per approved currency. Balances are **never
  silently combined or converted**. Conversion happens only through an explicit RiftEco
  exchange quote that the actor accepted.
- Changing a default affects future transactions only; history keeps the currency it was
  written in.
- Legacy `%townyadvanced_*%` balance placeholders report the organisation's default
  currency, because those placeholders have no currency parameter.

### 4.2 Integrity `[SPEC]`

Every monetary operation carries an idempotency key and is atomic. A retry after a
timeout must not double-charge. Disbanding an organisation settles its accounts
deterministically; the default policy pays the remaining balance to the leader, subject to
configuration.

---

## 5. Governance `[SPEC]`

Appointed leadership is the default. Towns and nations may opt into elections.

- Terms, nomination windows, campaign periods, voting periods, quorum, candidate
  eligibility and runoffs are all configurable.
- Town voting is one resident, one vote.
- Nation voting is configurable: one resident one vote, or one town one vote cast by its
  mayor or a named delegate.
- Ballots are secret and **one UUID may cast one ballot**, enforced at the storage layer.
- Vacancies produce an acting leader and a special election.
- If nobody stands, the incumbent continues under a configured no-candidate rule.
- Turning an established democracy back into an appointed government requires a
  referendum or an administrator, never a unilateral act by the incumbent.
- Leadership change updates roles, bank authority and (for nations) the capital
  **atomically** — there is no window in which the organisation has two leaders or none.
- Optional later: legislatures, proposals, referendums, laws, budgets, delegated offices.

---

## 6. War and shields `[DRAFT — approval required]`

The war state machine is **not specified here**. Towny has several mutually incompatible
war add-ons, so "how Towny does it" is not a specification. The proposal — declaration
requirements, preparation, protection, PvP and raiding rules, capture, scoring, land
transfer limits, surrender, occupation, victory and recovery — lives in
[docs/war-decisions.md](docs/war-decisions.md) and **must be approved before the final
war rules are implemented**.

War shields are specified alongside it in the same document.

---

## 7. Land regeneration `[SPEC]`

- Wilderness state is snapshotted before a chunk is claimed, subject to per-world
  configuration and material/entity allowlists.
- After unclaiming, the snapshot is restored gradually from a **persistent queue** that
  survives restarts.
- Optional wilderness explosion regeneration uses the same queue.
- Regeneration **never duplicates** inventories, spawners, shop data or protected
  entities. Anything owned by RiftShop or RiftSpawners is handed to those plugins, not
  re-placed from a snapshot.
- Work is rate-limited and, on Folia, dispatched to the region owning each chunk.
- Every restore is auditable through **RiftLogger**, which is the only audit integration.

> **CoreProtect is not used.** RiftLogger owns permanent audit records, so pointing block
> history at a second system would give operators two places to look and two retention
> policies to keep in step. The cost is real and worth stating: RiftLogger records *events*,
> not per-block change history, so there is no third-party inspect-and-rollback tool behind
> regeneration. The restore data itself never depended on one — it comes from RiftTowny's own
> `rt_regen_snapshot` — but if per-block forensics is ever wanted, the answer is a block
> record type in RiftLogger, not a second plugin.
- Operators get preview, pause, resume, status and repair tools.

---

## 8. Flight, RTP and safe locations `[SPEC]`

### 8.1 Ownership split

| Concern | Owner |
|---|---|
| Booster definitions, authoritative timers, vote/donation goals, queueing, caps | **RiftBoosters** |
| Territory, world, role, war and no-fly eligibility; handoff between territories | **RiftTowny** |
| Combat tags, boundary bounce-back, escape prevention, valid-kill determination | **RiftPvP** |

The community flight booster is server-wide: every eligible player flies, players joining
mid-booster get the remaining time, quitting does not pause the global timer, and the
remaining time is computed from persisted wall-clock timestamps so a rejoin or a server
transfer restores it correctly. On MariaDB it synchronises network-wide through
VelocitySrv; on SQLite it is limited to one backend and says so.

A combat tag from RiftPvP removes RiftTowny-managed flight immediately.

### 8.2 Soft Landing `[SPEC]`

Whenever RiftTowny-managed flight ends — expiry, revocation, combat tag, permission loss,
border crossing, world restriction, or an administrator — the player gets Soft Landing:

- ascent is disabled immediately;
- a controlled descent to safe ground or water;
- fall damage is suppressed **for that descent only**;
- elytra gliding is not interfered with;
- lava, void, solid blocks and hazardous objectives are avoided;
- offline expiry and reconnecting mid-air are handled on the next join;
- creative and spectator flight are never touched.

### 8.3 Safe destinations `[SPEC]`

One safe-location engine serves RTP, wilderness respawn and protected destination pads. A
destination is valid only if it is a solid natural surface with headroom, not ocean,
river, lava, powder snow, leaves, cave, roof, underground or void, and it satisfies the
configured sky-visibility, biome, elevation, slope, world border and distance rules. It
must also avoid claims and claim buffers, active wars, ruins, pending regeneration,
events, supply drops and protected destinations.

Searching is asynchronous; the teleport is Folia-safe; **the destination is revalidated
immediately before the teleport**, because the world can change during the search.

**RiftEssentials already owns `/rtp`.** RiftTowny does not register a competing command.
It supplies a `ClaimGuard` implementation and the safe-destination API instead — see
[INTEGRATION_CONTRACTS.md](INTEGRATION_CONTRACTS.md) §2.9.

---

## 9. Commands and interfaces `[SPEC]`

Familiar command trees: `/town` `/t`, `/nation` `/n`, `/resident` `/res`, `/plot` `/p`,
`/towny`, `/townyadmin` `/ta`, `/townyworld`, `/tc` `/nc` `/ac`, `/tfly` `/townyfly`
`/townyflight`, `/rtp` `/wild` (delegated), `/town top`, `/nation top`, `/towny warp`,
and `/rifttowny` for RiftTowny-specific administration.

New functionality is added *beneath* the familiar trees rather than in new top-level
commands.

**Parity rule, enforced by test:** every meaningful action is reachable through all four
of commands with full tab completion, clickable Adventure chat components, Java inventory
GUIs, and native Floodgate Bedrock forms. **No feature is GUI-only.**

Canonical permissions are `rifttowny.*`. Optional `towny.*` aliases may be enabled in
configuration for servers migrating permission sets.

---

## 10. Public API `[SPEC]`

`rifttowny-api` is a published, versioned artifact. It exposes:

- read-only, immutable views of residents, towns, nations, claims, areas, roles,
  relationships and wars;
- asynchronous economy and mutation methods;
- territory lookup and effective flag resolution;
- safe-location and RTP queries;
- typed cancellable pre-events where cancelling is safe, and immutable post-events;
- capability discovery and API version negotiation.

Internal mutable entities are never exposed. A consumer holding an API object can never
mutate RiftTowny state except through a service method that performs its own permission
and validation checks.

---

## 11. Security and reliability `[SPEC]`

- Permission checks live at **service boundaries**, not only in command handlers. Calling
  the API is not a way around them.
- Monetary and cross-server operations are idempotent, keyed, and safe to retry.
- All administrative mutations are audit-logged with actor, target, before and after.
- Names are normalised and validated against a reserved-word list; input length and
  character class are bounded.
- Rate limits apply to claim, invite, deposit and message operations.
- Degradation is graceful: a missing optional integration disables only what depends on
  it.
- Operators get backup, integrity-check, repair, migration and dry-run administrative
  commands, plus `/rifttowny status` for performance and cache diagnostics.
- No secrets are logged. **No live player coordinates appear in public bounty output** —
  bounty search regions are approximate by default.
- Shutdown and partial failure are explicitly handled: in-flight work is either completed
  or replayable from the outbox.
