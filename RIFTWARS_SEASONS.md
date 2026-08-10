# RiftSeasons — Specification

`RiftSeasons.jar`. Depends on RiftTowny only. Released **second**, before RiftWars, so that
seasons exist as a general competitive framework rather than as an appendix to war.

RiftWars integrates with it; it does not require RiftWars. A server can run a purely
economic or cultural season with no combat at all.

---

## 1. Why RiftSeasons ships before RiftWars

A season is a scoring, scheduling and standings framework. War is one thing that can feed
it. Building it war-first would bake war-shaped assumptions into the schema — a
`siege_id` column on a score row, a standings table that only knows about combat — and the
first non-combat season would need a migration.

Shipping it second, with RiftWars as its first *consumer* rather than its author, is what
keeps `SeasonScoringContributor` a real interface instead of a formality.

## 2. Lifecycle

```
PRESEASON → REGISTRATION → CAMPAIGN → FINALS → RECOVERY → ARCHIVE
```

| Phase | What happens | Ends when |
|---|---|---|
| `PRESEASON` | Configuration published, rules previewable, no scoring | Operator opens registration |
| `REGISTRATION` | Organisations register, rosters submitted, divisions assigned | Registration deadline, or operator |
| `CAMPAIGN` | Scoring live, battle windows active, standings update | Campaign end date |
| `FINALS` | Championship bracket among qualifiers | Bracket concludes |
| `RECOVERY` | Scoring frozen, rewards distributed, shields granted, overlays wound down | Recovery period elapses |
| `ARCHIVE` | Season immutable, Hall of Fame written, tables archived | Terminal |

Every transition is transactional, idempotent, restart-safe, audited and published as a
typed event — the same guarantees as a siege transition, and for the same reason: a season
that advanced but never paid its rewards is unrecoverable without them.

## 3. Participation

Town · nation · federation (needs `FederationCapability`) · coalition · division.

Controls: roster locks · minimum residency before a resident counts · alliance-change
restrictions during a season · war-profile allowlists per division · membership caps.

**Roster lock is the anti-mercenary rule.** Without it a season is decided by who can
recruit the strongest players on the final weekend.

## 4. Scoring

A **ledger**, not a total. Every entry records source, contributor, amount, timestamp,
correlation id and the idempotency key that produced it. The standing is a projection.

Score sources — each an optional contributor, each independently disableable:

| Source | Provider | Notes |
|---|---|---|
| Victory, capture, defence | RiftWars | via `SeasonScoringContributor` |
| Bounties | RiftEvents | |
| Logistics | RiftInfrastructure | needs `LogisticsCapability` |
| Events and crises | RiftEvents | |
| Economic | RiftEco + RiftTowny | trade volume, treasury growth, tax throughput |
| Seasonal objectives | RiftSeasons | its own |
| Diplomacy | RiftCivics | needs advanced `DiplomacyCapability` |

### 4.1 Fairness, which is not optional

- **Daily opponent limits** — the same pairing cannot be farmed.
- **Anti-farming** — repeated scoring against the same organisation decays.
- **Anti-snowball** — marginal value of each point decreases as a lead grows.
- **Catch-up** — trailing organisations receive a bounded multiplier, capped so it can
  never overtake genuine performance.
- **Alt detection** feeds eligibility; an alt roster does not multiply a score.
- **No retroactive scoring** after a phase snapshot closes, except an audited
  administrative correction.

## 5. Territory overlay

Seasonal conquest uses a **separate resettable overlay** (`rs_overlay_claim`).

**Permanent RiftTowny claims are never touched by a season, in either direction, by
default.** A season ending resets the overlay and nothing else. The only path from overlay
to permanent territory is an explicit, supported, previewed administrative conversion —
never automatic, never a side effect of the season ending.

This is the single most important guarantee in this document. A player whose town lost a
season must still have their town.

## 6. Rewards and history

Rewards through RiftEco and RiftCosmetics; cosmetic and economic only, never competitive
combat advantage. Hall of Fame and lifetime history are permanent and survive archival.
Discord season reports, standings and championship results route through VelocitySrv.

## 7. Holiday pauses and end-of-season safety

An operator may pause a season; timers, windows and expiries all shift rather than expire
during a pause. End-of-season teardown is a checklist, executed transactionally and
resumable: freeze scoring → settle rewards → grant recovery shields → wind down overlays →
archive → announce. A crash partway through resumes at the first incomplete step.

## 8. Commands

`/season` · `/season status` · `/season register` · `/season standings` ·
`/season division` · `/season finals` · `/season rewards` · `/season map` ·
`/season admin *`.

Full tab completion, clickable chat, Java GUI, Bedrock forms and PAPI for all of them.

## 9. Completion gate

RiftSeasons: Ages is not complete until: every lifecycle transition is restart-tested; the
ledger reconciles to the standings under concurrent contribution from two backend servers;
fairness rules have tests that demonstrate the exploit they prevent; the overlay reset is
proven not to touch a permanent claim; and Java and Bedrock parity is tested.
