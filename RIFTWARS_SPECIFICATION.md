# RiftWars — Specification

`RiftWars.jar`. Depends on the public RiftTowny API; optionally integrates with
RiftSeasons. Never compiles against RiftInfrastructure or RiftCivics — it consumes their
capability interfaces from `rifttowny-api`.

First public release: **RiftWars: Frontier**, third in the release order, containing the
composable War Profile engine with **exactly one publicly enabled profile: Siege**.

**Clean-room.** SiegeWar is referenced for *behavioural research only* — what players
expect a siege to feel like. No SiegeWar code, message text, configuration, asset,
documentation or internal design is copied, ported, translated or adapted. Familiar public
behaviour is reproduced through an original architecture.

---

## 1. Relationship to the approved war decisions

`docs/war-decisions.md` was approved 2026-08-09, all twelve decisions as recommended.
Those decisions **survive the move from RiftTowny to RiftWars** — they were about rules,
not about which jar holds them. This section records exactly how they map, and the two
places the addendum narrows them.

### 1.1 The state machine — D-01 refined, not replaced

| Approved D-01 | Frontier lifecycle | Note |
|---|---|---|
| *(implicit)* | `ELIGIBILITY_CHECK` | **New.** D-01 folded eligibility into declaration; making it a state means a refused declaration is auditable |
| `DECLARED` | `DECLARED` | unchanged |
| `PREPARATION` | `PREPARATION` | unchanged |
| `ACTIVE` | `BATTLE_SESSION` ⇄ `BETWEEN_SESSIONS` | **Refinement, not a reversal.** D-04 already approved scheduled combat windows with full protection outside them; splitting `ACTIVE` makes that approved behaviour a persisted state instead of a flag |
| `TERMS` | `RESOLUTION` | renamed. D-01's substance — every effect applied here, atomically, once — is preserved |
| `OCCUPATION` / `RECOVERY` | `OCCUPATION_OR_RECOVERY` | merged; the branch is chosen by the outcome |
| `PEACE` | `CLOSED` | **New terminal state.** D-01 looped back to `PEACE`; an explicit terminal state makes a finished siege distinguishable from one that never started |

**Verdict: compatible.** No approved decision is contradicted.

### 1.2 Where the addendum narrows an approved decision

Both narrowings are the addendum winning, as instructed. Both are recorded so nobody
later reads `war-decisions.md` and implements something wider.

| Decision | Approved | Frontier ships | Why the narrowing is safe |
|---|---|---|---|
| **D-02** participants | Nation *or independent town* may declare, against a nation *or independent town* | **Nation attacks town** by default; independent-town participation is configurable and off | Narrower is a subset. The profile engine expresses the wider case; only the shipped default changes |
| **D-06** settlement | Land transfer capped at 25%, homeblock never transferable | **Siege transfers no land at all.** Outcomes are tribute, limited plunder, occupation | D-06's cap remains the ceiling for any future profile that transfers land. Shipping zero transfer first means the riskiest approved mechanic is proven in a later profile, not the first |

D-03, D-04, D-05, D-07, D-08 and D-09 carry over unchanged. Q-02a, Q-04a and Q-05a become
War Profile fields rather than global settings, which is strictly more flexible.

## 2. The War Profile engine

A war profile is a composition, not a subclass:

```
participants + justification + theater + objectives + schedule
             + protection rules + scoring + consequences
```

Each component is an independently configured, independently tested value object. The
Siege profile is a particular composition of them — **not a hard-coded state machine with
a profile field bolted on.** The test that proves this: Siege must be expressible entirely
through the same YAML an administrator would write.

| Component | What it fixes | Backing table |
|---|---|---|
| `participants` | Who may attack, who may be targeted, coalition and federation rules | `rw_profile` |
| `justification` | What makes a declaration legal — casus belli, treaty violation, seasonal registration | `rw_profile` |
| `theater` | Where it happens — worlds, regions, seasonal overlay or permanent claims |`rw_profile` |
| `objectives` | Composed from reusable objective components (§3) | `rw_objective` |
| `schedule` | Preparation length, battle windows, timezones, session count | `rw_session` |
| `protection` | PvP, build, break, container, explosion, fire, fluid, piston, flight rules | `rw_profile` |
| `scoring` | Which components score, their weights, their caps | `rw_score_entry` |
| `consequences` | Tribute, plunder, land transfer, occupation, recovery, immunity | `rw_outcome` |

### 2.1 Authoring and safety

Administrators define the **legal boundaries and templates**; authorised leaders configure
a permitted war within them. Both through commands, clickable chat, Java GUI and Bedrock
forms.

Required before any profile may be used: clone, import, export, **validate**, **dry run**,
and a **full rule preview that the defender sees before acceptance**. A profile that fails
validation cannot be selected. A dangerous combination — for example enabling container
access inside town claims — requires an explicit acknowledgement, not a silent accept.

### 2.2 The frozen snapshot

At `DECLARED`, the entire resolved rule set is serialised into `rw_rule_snapshot` and the
siege references that row for its whole life. Editing the profile afterwards affects future
wars only.

This is what makes a war auditable: "the rules changed halfway through" is otherwise
impossible to disprove, and with a snapshot it is impossible to happen.

## 3. Reusable components, not many state machines

The long list of war types in the addendum — total war, blitz, convoy, blockade, relic
capture, leader hunt, payload escort, extraction, defusal, monument control, boss races,
artifact assembly and the rest — is **not** dozens of state machines. It is combinations of
a small set of components.

**Objective primitives:** `CONTROL_POINT` · `HOLD_DURATION` · `CAPTURE_CARRY` ·
`ESCORT_PAYLOAD` · `DESTROY_TARGET` · `DEFUSE_TARGET` · `ASSEMBLE_SET` ·
`EXTRACT_TO_ZONE` · `SURVIVE_DURATION` · `ELIMINATE_TARGET` · `REACH_THRESHOLD`.

**Scoring primitives:** `VALID_KILL` · `OBJECTIVE_TICK` · `OBJECTIVE_COMPLETE` ·
`SUCCESSFUL_DEFENCE` · `EVENT_CONTRIBUTION` · `BOUNTY_CLAIM` · `PENALTY` ·
`ADMIN_CORRECTION`.

**Schedule primitives:** `SESSION_WINDOW` · `PREPARATION` · `COOLDOWN` ·
`TICKET_POOL` · `SUDDEN_DEATH`.

**Consequence primitives:** `TRIBUTE` · `PLUNDER_LIMITED` · `TERRITORY_TRANSFER` ·
`OCCUPATION` · `IMMUNITY` · `REPARATION_SCHEDULE` · `SANCTION` (needs
`SanctionCapability`).

A "convoy war" is `ESCORT_PAYLOAD` + `SESSION_WINDOW` + `TRIBUTE`. A "relic capture" is
`ASSEMBLE_SET` + `EXTRACT_TO_ZONE`. Adding one of the listed war types should mean writing
a YAML profile, not a Java class. **If it needs a new Java class, the decomposition was
wrong** — that is the acceptance test for the engine.

## 4. The Siege profile — Frontier

### 4.1 Lifecycle

```
ELIGIBILITY_CHECK → DECLARED → PREPARATION → BATTLE_SESSION ⇄ BETWEEN_SESSIONS
                                                    ↓
                                              RESOLUTION → OCCUPATION_OR_RECOVERY → CLOSED
```

Every transition is **transactional, idempotent, restart-safe, audited, published as a
typed event, and safe across multiple backend servers.**

How each property is achieved, concretely:

- **Transactional** — the state row, the ledger rows and the outbox row are written in one
  transaction. There is no window where the siege advanced but the announcement was lost.
- **Idempotent** — every transition carries a key of
  `siege_id + from_state + to_state + sequence`. Replaying it is a no-op.
- **Restart-safe** — state lives in `rw_siege`, never in memory. On enable, every siege not
  `CLOSED` is reconciled against wall-clock time: a session that should have started while
  the server was down starts now or is skipped per configuration, and the decision is
  logged. Restart is tested **in every state**.
- **Multi-server safe** — a transition takes a MariaDB advisory lock on the siege id. On
  SQLite, sieges are single-server and startup says so.

### 4.2 ELIGIBILITY_CHECK

A real state, so a refusal is recorded and explainable. Validates, in order, short-circuiting:

attacker authority (leader, or `GovernmentApprovalCapability` if present) · target
eligibility · relationship state · new-town protection · recovery immunity · war shields ·
peaceful status · capital rules · active siege limits · cooldowns · minimum organisation
age · minimum activity · required treasury balance · war bond affordability · existing wars ·
seasonal registration · world and server eligibility.

Each check returns a named reason. `/war siege declare` shows the first failure and the
full list on request — "you cannot declare" with no reason is a support ticket.

### 4.3 DECLARED

- War bond taken into **RiftEco escrow** under an idempotency key of
  `siege:bond:<siege_id>`. If escrow fails, the siege does not enter `DECLARED`.
- Rule snapshot frozen.
- In-game and Discord announcement; Discord thread created when configured.
- Concurrent declarations against the same target resolve to exactly one winner via the
  advisory lock plus a unique constraint on `(target_id, state != CLOSED)`.

### 4.4 PREPARATION

Protection fully intact; **no siege-zone PvP yet**. Participants and reserves register.
Alliance and organisation hopping is blocked for both sides. Map markers appear. Permitted
surrender, negotiation or administrative cancellation may end the siege here, refunding the
bond per profile.

### 4.5 The siege banner and objective zone

Created near the target's permitted border. Requirements, all hard:

- Terrain and claim ownership validated before placement.
- Never placed over a protected destination, public warp, regeneration queue, event zone or
  unsafe terrain.
- **Never overwrites a protected container, spawner, shop or player asset.** The placement
  check runs against RiftShop and RiftSpawners where present, and refuses rather than
  displaces.
- Exact banner and zone state persisted; generated visuals rebuilt safely after restart.
- Unauthorised relocation or destruction prevented.
- Shown on BlueMap, Dynmap and squaremap; status readable from Java and Bedrock.

### 4.6 BATTLE_SESSION

On start: RiftPvP activates combat rules · PvP protection suspended **only inside the
authorised siege zone** · town protection stays active everywhere else · town storage and
normal builds stay protected · RiftTowny flight and global flight boosters are removed for
participating combat-tagged players, with **Soft Landing** · boss bar, action bar, chat and
map update · Discord receives a session-start embed.

On end: normal protection restored · score committed transactionally · results announced ·
session summary to participants · Discord receives winner, score delta and remaining status
· siege moves to `BETWEEN_SESSIONS` or `RESOLUTION`.

### 4.7 Scoring

Component-based and configurable. Frontier sources: valid RiftPvP kills · siege-banner
control · time controlling the objective · successful defence · RiftEvents objectives ·
bounties · configured penalties · administrative corrections, always audited.

**A complete ledger is stored, never just the total.** Guards: alt farming · repeated
victim farming · allied and same-organisation kills · invalid combat · duplicate events ·
cross-server duplicate scoring · any change after the session snapshot closes.

### 4.8 Protection model — the default

No unrestricted griefing. No general container theft. No automatic destruction of town
builds. **Only explicitly authorised objective interactions bypass protection**, and normal
protection is restored the moment the session ends. Admin-authored profiles may later widen
this; dangerous combinations require a warning and explicit approval.

**RiftTowny remains the authority for effective territory flags.** RiftWars supplies a
war override that the RiftTowny resolver consults at its documented position in the
resolution order — it does not patch, wrap or bypass the resolver.

### 4.9 RESOLUTION and outcomes

Attacker victory · defender victory · draw · surrender · cancellation. Consequences:
tribute (capped, RiftEco-settled) · limited plunder · occupation · recovery · post-siege
immunity · anti-repeat cooldown.

**Occupation is metadata and governance state.** It does not delete the town, transfer
player membership, or rewrite claims. The occupied town keeps its identity, bank, residents
and roles.

**No land changes hands in the Siege profile.** See §1.2.

### 4.10 Shields

New-town · recovery · purchased or emergency where enabled. Nation-wide or independent-town
scope. Treasury-funded extensions with escalating cost, duration caps and cooldowns. **No
activation during an active siege or preparation.** No stacking. Anti-nation-hopping and
anti-recreation keyed on the organisation UUID and the founder set. Status visible in GUI,
Bedrock forms, chat, PAPI, maps, Discord and logs — a shield is never a hidden advantage.

## 5. Commands

`/war` · `/war siege` · `/war status` · `/war list` · `/war surrender` · `/war shield` ·
`/war season` · `/war admin simulate`.

Aliases `/sw` and `/swa` are registered **only when they do not conflict**, and the
conflict check runs at enable rather than being assumed.

Full tab completion, clickable chat, Java GUI, Bedrock forms, PAPI, RiftChat, RiftLogger
and map integration are required for each.

Native `%riftwars_*%` placeholders ship. **SiegeWar-compatible placeholder aliases are
documented separately, disabled by default, and never registered blindly** — an alias is
only offered once its output has a golden test proving it matches.

## 6. Discord war feed

VelocitySrv owns delivery and credentials. RiftWars writes outbox rows only.

Events: declaration · preparation start and end · mobilisation · battle-session start · PvP
protection suspension · live score milestones · session conclusion · protection restoration
· immunity start and expiry · victory, defeat or draw · capture and occupation · plunder or
tribute availability · surrender · ceasefire · peace agreement · liberation · cancellation ·
administrative intervention · season standings and championship results.

Embed fields: participants · profile · objectives · current score · score change · start
and end timestamps · winner · consequences · recovery duration · map link · war-details
link · leaderboard link.

Routing: a global `#war-feed`, private organisation channels, a staff audit channel, and an
optional thread per siege. Configurable templates, colours, mentions and severity.
Persistent MariaDB outbox with retry, deduplication, correlation IDs, and **updating an
existing message as the siege progresses** rather than spamming a new one per transition.

> Per-organisation channels depend on `RT-MOD-DISCORD-CHAN`, which is **BLOCKED** — see
> [INTEGRATION_CONTRACTS.md](INTEGRATION_CONTRACTS.md) §2.6. The global feed, staff channel
> and threads do not depend on it.

## 7. Required integrations for Frontier

Verified APIs only: RiftTowny · RiftSeasons · RiftEco · RiftPvP · RiftEvents · RiftBoosters
· RiftChat · RiftLogger · VelocitySrv · PlaceholderAPI · BlueMap · Dynmap · squaremap.

Optional and later: RiftInfrastructure · RiftCivics · RiftShop · RiftSpawners ·
RiftCosmetics · ExperienceManager · RiftbreakerRifts · RiftPunishments · NPC providers ·
anti-cheat providers.

**A missing optional integration disables only its dependent capability.** Every adapter
carries a capability check and a version check.

## 8. Test requirements

Nothing below is optional for Frontier to be called complete:

declaration eligibility, every named check · concurrent declaration attempts · war-bond
escrow including failure and refund · every preparation transition · session scheduling
across timezones · **restart during every siege state** · duplicate proxy events ·
duplicate scoring · PvP activation and restoration · protection boundaries, including a
source in one claim and a target in another · banner persistence across restart ·
surrender · tribute · occupation · recovery · shield abuse attempts · organisation hopping ·
offline defender rules · season scoring · Discord outbox retry · MariaDB network locking ·
SQLite single-server behaviour · Folia scheduler correctness · Java and Bedrock parity.

`/war admin simulate` runs a full siege against a dry-run ledger, touching **no** real
economy balance, season standing or siege record.

## 9. Completion gate

RiftWars: Frontier is not complete until **all** of: the full Siege lifecycle works end to
end; the test list in §8 passes; documentation is current; the Discord feed delivers and
updates; the Java GUI and Bedrock forms reach parity; and failure recovery is demonstrated
by killing the server in each state and restarting it.

Later war profiles are not started until Frontier is stable.
