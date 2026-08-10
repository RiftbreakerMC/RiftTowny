# Release Order and Server Eras

Two different things share the word "era" in this project, and conflating them causes
confusion. They are separated here.

- **Release order** — the order the five plugins become public. Fixed. Section 1.
- **Server eras** — a per-server, operator-controlled gate that unlocks features to
  *players* over time. Configurable, never automatic. Section 3.

A feature can be released (shipped in a jar) and still be locked (its server era has not
been approved). Both must be true before players see it.

---

## 1. Public release order

`RiftTowny → RiftSeasons → RiftWars → RiftInfrastructure → RiftCivics`

| # | Release | Theme | Contains | Gate to start |
|---|---|---|---|---|
| 1 | **RiftTowny: Founding** | Founding | Core + the optional modules a town server needs on day one | — |
| 2 | **RiftSeasons: Ages** | Ages | Season lifecycle, standings, divisions, rosters, rewards, Hall of Fame | RiftTowny stable in production for one full season-length window |
| 3 | **RiftWars: Frontier** | Frontier | War Profile engine + **the Siege profile only**, publicly enabled | RiftSeasons released; RiftTowny diplomacy and shields stable |
| 4 | **RiftInfrastructure: Industry** | Industry | Assets, warehouses, logistics, facilities, reconstruction, procurement | RiftWars: Frontier stable |
| 5 | **RiftCivics: Nations** | Nations | Governments, legislatures, federations, treaties, courts, sanctions, peace conferences | RiftInfrastructure released |
| 6 | **RiftWars: Grand Strategy Update** | Grand Strategy | The remaining war profiles, campaigns, doctrines, espionage, war analytics | RiftCivics released; Siege profile proven over a full season |

RiftWars may be **developed and tested** at any time — its engine is needed to prove the
Siege profile is genuinely composable rather than hard-coded. Its **first public version**
still ships third.

### 1.1 What "stable" means as a gate

Not a feeling. All of: the release's acceptance criteria in
[FEATURE_CATALOG.md](FEATURE_CATALOG.md) met; `mvn clean verify` green; a restart test
passed in every persistent state the release introduces; Java and Bedrock parity tested;
and no open `critical` or `high` entry in [docs/risk-register.md](docs/risk-register.md)
owned by that release.

## 2. Where the original six phases went

The original Phase 1–6 plan predates the split into five plugins. Mapping:

| Original | Now |
|---|---|
| Phase 1 Foundation | **RiftTowny: Founding** — shipped, unchanged |
| Phase 2 Core replacement | **RiftTowny: Founding** — active work |
| Phase 3 Civic systems | **RiftTowny: Founding**, except elections/laws which move to RiftCivics |
| Phase 4 Combat and events | Split: flight/Soft Landing/bounties/supply drops stay in RiftTowny; **war moves to RiftWars: Frontier** |
| Phase 5 Ecosystem integrations | **RiftTowny: Founding** — chat, Discord, shops, spawners, mcMMO, placeholders |
| Phase 6 Advanced modules | Distributed across RiftInfrastructure, RiftCivics and RiftWars: Grand Strategy |

**The approved war decisions are not lost.** `docs/war-decisions.md` (approved 2026-08-09,
all twelve as recommended) is carried into
[RIFTWARS_SPECIFICATION.md](RIFTWARS_SPECIFICATION.md), which maps each D-ID onto the new
Siege lifecycle and records the one place the addendum overrides it — see §4 there.

## 3. Server eras

A per-server progression an operator controls. Default set, all renameable, reorderable,
removable and extensible:

| # | Era | Unlocks, by default |
|---|---|---|
| 1 | Founding | Residents, towns, claims, protection, roles, banks, chat |
| 2 | Expansion | Outposts, areas, plots, districts, taxes, upkeep, leaderboards, property |
| 3 | National | Nations, nation banks, nation roles, capitals |
| 4 | Diplomatic | Diplomacy, treaties, embassies, open borders, federations |
| 5 | Conflict | Shields, war eligibility, RiftWars profiles |
| 6 | Industrial | Infrastructure, logistics, assets, projects, zoning |
| 7 | Global | Federations at scale, World Council, cross-server organisations, crises |

### 3.1 Unlock conditions

A module declares which era it belongs to. An era advances by exactly one of:

`MANUAL` (default) · `SEASON` · `DATE` · `SERVER_AGE` · `ACTIVE_POPULATION` ·
`ORGANISATION_COUNT` · `PROJECT_COMPLETION` · `PRIOR_ERA_COMPLETE`

**Manual approval is the default and nothing advances automatically unless an operator
explicitly configures it to.** An era transition is announced, audited, reversible in the
sense that the era can be set back, and **never destructive**: data written in a later era
survives a rollback to an earlier one, it simply stops being reachable.

### 3.2 Population scaling

Systems scale on **rolling active population**, not registered accounts:

claim limits · organisation levels · outpost allowance · facility caps · event frequency ·
economy sources and sinks · war divisions · battle roster sizes · objective counts ·
seasonal league sizes.

Guards, all mandatory: alt detection feeds the active count, not the raw player count;
minimum residency before a resident counts; hysteresis so a population dip does not
immediately strip claims; a performance ceiling that caps scaling regardless of population.

### 3.3 The rule that protects players

**No era transition, in either direction, may delete a claim, an organisation, an asset or
a balance.** If a rollback would put a server over a now-lower limit, the excess is marked
`OVER_LIMIT` and frozen — it cannot grow, and nothing is taken away.

## 4. Era assignment summary

| Release | Server eras it can serve |
|---|---|
| RiftTowny: Founding | 1–7 (core in 1; modules across all) |
| RiftSeasons: Ages | 2+ |
| RiftWars: Frontier | 5+ |
| RiftInfrastructure: Industry | 6+ |
| RiftCivics: Nations | 4+ (governments), 7 (World Council) |
| RiftWars: Grand Strategy | 5+ |

Per-feature era assignment is in [FEATURE_CATALOG.md](FEATURE_CATALOG.md).
