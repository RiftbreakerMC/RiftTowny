# RiftTowny Ecosystem — Module Graph

Authoritative as of 2026-08-10. Supersedes the single-plugin assumption in the original
brief: war, seasons, infrastructure and civics move out of `RiftTowny.jar` into their own
plugins. Nothing already built is discarded — Phase 1 lands unchanged in RiftTowny core.

---

## 1. Plugins

| Plugin | Jar | Depends on | Provides to others | Status |
|---|---|---|---|---|
| **RiftTowny** | `RiftTowny.jar` | nothing mandatory | organisations, territory, protection, roles, banks, diplomacy state, stable IDs, `rifttowny-api` | Phase 1 shipped |
| **RiftSeasons** | `RiftSeasons.jar` | RiftTowny | seasons, standings, divisions, rosters, seasonal overlays | not started |
| **RiftWars** | `RiftWars.jar` | RiftTowny; optional RiftSeasons | war profiles, sieges, war analytics | not started |
| **RiftInfrastructure** | `RiftInfrastructure.jar` | RiftTowny | `InfrastructureCapability`, `LogisticsCapability`, `AssetCapability`, `ReconstructionCapability` | not started |
| **RiftCivics** | `RiftCivics.jar` | RiftTowny | `GovernmentApprovalCapability`, `FederationCapability`, `SanctionCapability`, `PeaceConferenceCapability`, advanced `DiplomacyCapability` | not started |

The fifteen existing Rift plugins stay independently deployed and are integrated through
their real public APIs only. The web portal is a separately deployed frontend, not a
gameplay plugin; RiftTowny exposes a scoped, secured read API to it and nothing more.

## 2. Dependency direction — acyclic by construction

```
                        ┌──────────────┐
                        │  RiftTowny   │  organisations · territory · protection
                        │   (core)     │  roles · banks · diplomacy state · IDs
                        └──────┬───────┘
             ┌─────────────┬───┴────┬──────────────┬───────────────┐
             ▼             ▼        ▼              ▼               ▼
     ┌─────────────┐ ┌──────────┐ ┌──────────────────┐ ┌────────────────┐
     │ RiftSeasons │ │ RiftWars │ │RiftInfrastructure│ │   RiftCivics   │
     └──────┬──────┘ └────▲─────┘ └────────┬─────────┘ └───────┬────────┘
            │             │                │                   │
            └─optional────┘                └──── capability ────┘
                          ▲                        interfaces
                          └────────────────────────────┘
```

Rules, enforced by an architecture test in each repo:

1. **RiftTowny never compiles against any of the four.** It has no idea they exist.
2. **RiftWars never compiles against RiftInfrastructure or RiftCivics.** It consumes their
   capability interfaces, which live in `rifttowny-api`, so the provider can arrive later
   without RiftWars changing.
3. **RiftSeasons never compiles against RiftWars.** RiftWars registers scoring
   contributions with RiftSeasons through a `SeasonScoringContributor` interface published
   by RiftSeasons; the direction is RiftWars → RiftSeasons only.
4. No plugin reaches into another's storage.

### 2.1 Why the capability interfaces live in `rifttowny-api`

They are the shared vocabulary. If `FederationCapability` lived in `RiftCivics.jar`,
RiftWars would need RiftCivics on its compile classpath to *ask whether it is present* —
which is exactly the cycle we are avoiding. Publishing the interface from the one artifact
everybody already depends on keeps RiftWars compiling and running with no provider at all.

### 2.2 Capability interfaces

Each is a `sealed`-free plain interface in
`net.riftbreaker.rifttowny.api.capability.provider`, registered by its provider at enable
and discovered through `CapabilityRegistry`.

| Interface | Provider | Consumer | RiftWars behaviour with no provider |
|---|---|---|---|
| `DiplomacyCapability` | RiftTowny (basic), RiftCivics (advanced) | RiftWars, RiftSeasons | Basic ally/enemy/neutral from RiftTowny. No treaties, arbitration or sanctions. |
| `GovernmentApprovalCapability` | RiftCivics | RiftWars | Declaration authority is the nation leader alone. No council, legislature or referendum path. |
| `FederationCapability` | RiftCivics | RiftWars, RiftSeasons | Wars are nation-vs-town only. No coalitions, no federation participation. |
| `SanctionCapability` | RiftCivics | RiftWars | Settlement offers tribute and occupation only. No embargo or sanction terms. |
| `PeaceConferenceCapability` | RiftCivics | RiftWars | Peace is a two-party surrender or timeout. No multi-party conference. |
| `InfrastructureCapability` | RiftInfrastructure | RiftWars | No facility objectives, no strategic assets. |
| `LogisticsCapability` | RiftInfrastructure | RiftWars | No supply lines, convoys or field camps. Reinforcement is a flat ticket count. |
| `AssetCapability` | RiftInfrastructure | RiftWars, RiftCivics | Plunder is treasury-only. No warehouse or asset transfer. |
| `ReconstructionCapability` | RiftInfrastructure | RiftWars | Recovery is a shield plus a timer. No repair queue or relief fund. |

**The degradation contract.** A missing provider disables its capability and nothing else.
Installing a provider later must activate the advanced path **without migrating war
records or invalidating existing sieges** — which is why every capability is consulted at
decision time and its answer recorded in the frozen rule snapshot, never assumed at
schema-design time.

## 3. RiftTowny internal modules

One jar. Optional modules are configuration, not separate downloads.

- `plugins/RiftTowny/modules.yml` — the on/off register plus per-module load order.
- `plugins/RiftTowny/modules/<module>.yml` — that module's own settings.
- `plugins/RiftTowny/lang/<module>_<locale>.yml` and `gui/<module>.yml` where the module
  has its own strings or menus.

### 3.1 Required core — cannot be disabled

| Module ID | Name | Phase 1 status |
|---|---|---|
| `RT-CORE-STORAGE` | Storage, migrations, outbox, idempotency | shipped |
| `RT-CORE-SCHED` | Paper/Folia scheduler abstraction | shipped |
| `RT-CORE-CONFIG` | Configuration and MiniMessage messages | shipped |
| `RT-CORE-API` | Public API, version negotiation, capability registry | shipped |
| `RT-CORE-RESIDENT` | Residents | Phase 2 |
| `RT-CORE-TOWN` | Towns | Phase 2 |
| `RT-CORE-NATION` | Nations | Phase 2 |
| `RT-CORE-CLAIM` | Chunk claims and areas | Phase 2 |
| `RT-CORE-FLAGS` | Flag resolver and protection listeners (renamed from `RT-CORE-PROTECT`, which read as the CoreProtect plugin) | Phase 2 |
| `RT-CORE-ROLE` | Roles and permissions | Phase 2 |
| `RT-CORE-CMD` | Command framework and tab completion | Phase 2 |
| `RT-CORE-UI` | GUI framework, clickable chat, Bedrock form foundation | Phase 2 |

### 3.2 Optional modules — `modules.yml`

`RT-MOD-BANK`, `RT-MOD-TAX`, `RT-MOD-ELECTION`, `RT-MOD-DIPLOMACY`, `RT-MOD-REGEN`,
`RT-MOD-SAFELOC`, `RT-MOD-DESTINATION`, `RT-MOD-LEADERBOARD`, `RT-MOD-FLIGHT`,
`RT-MOD-CHAT`, `RT-MOD-DISCORD`, `RT-MOD-MAP`, `RT-MOD-PAPI`, `RT-MOD-SHOP`,
`RT-MOD-SPAWNER`, `RT-MOD-MCMMO`, `RT-MOD-ZONING`, `RT-MOD-DEPARTMENT`,
`RT-MOD-PROGRESSION`, `RT-MOD-CULTURE`, `RT-MOD-ONBOARD`, `RT-MOD-NPC`,
`RT-MOD-FACILITY`, `RT-MOD-CALENDAR`, `RT-MOD-INBOX`, `RT-MOD-PORTAL`,
`RT-MOD-TEMPLATE`, `RT-MOD-ERA`, `RT-MOD-JUSTICE`, `RT-MOD-PROPERTY`.

Full definitions in [FEATURE_CATALOG.md](FEATURE_CATALOG.md).

### 3.3 Enable and disable rules

- **Stateless modules** (`RT-MOD-PAPI`, `RT-MOD-MAP`, `RT-MOD-CHAT`) toggle live.
- **Stateful modules** require a restart in both directions. The toggle is written, the
  operator is told a restart is needed, and nothing half-loads.
- **Disabling never deletes data.** Its tables stay, its rows stay, its commands stop
  registering and its listeners unregister. Re-enabling picks the data back up.
- A module whose hard dependency is disabled refuses to enable and says which one.
- `/rifttowny modules` reports every module's declared state, actual state, and the reason
  for any difference.

## 4. Module and jar boundaries — the rule that decides

A capability belongs in **RiftTowny core** if territory, protection or organisation
identity is meaningless without it. It belongs in an **optional RiftTowny module** if it
extends organisations but a server could reasonably run without it. It belongs in a
**separate plugin** if it has its own lifecycle, its own release cadence, and its own
storage that a RiftTowny-only server should never pay for.

Sieges have their own lifecycle and their own tables, so RiftWars is a plugin. Taxes are
a setting on an organisation that already exists, so taxes are a module.
