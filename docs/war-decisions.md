# RiftTowny — War Decisions

> **STATUS: APPROVED 2026-08-09.** Every decision was accepted as recommended.
> Phase 4 war implementation is unblocked. It remains scheduled after Phases 2 and 3 —
> approval removes the blocker, it does not move the work forward in the queue.
>
> "How Towny works" was not a usable specification: Towny ships several mutually
> incompatible war add-ons (flag war, event war, siege-style), each with different
> declaration rules, different capture mechanics and different consequences for losing.
> Picking one silently would have baked a design decision into the schema that is
> expensive to reverse. Each decision below therefore records the choice, the reasoning,
> and the alternative that was weighed against it.

**Changing an approved decision after Phase 4 ships means a migration against live war
state**, and in the case of D-06 against land that has already changed hands. If any of
these read wrong once they are in front of players, raise it before Phase 4 rather than
after.

---

## 0. Framework

These carry no design risk and were never blocked on approval:

- The `WarState` persistence tables and the state-machine *framework* (transitions as
  data, so the rules below become configuration rather than code).
- Shield persistence and the anti-abuse rules in §7, which are mechanical.
- The `WarStateChanged` typed event and outbox rows.
- Flight revocation on entering a war zone (RiftPvP contract, already agreed).

The **rule content** — who may declare, what is protected, what changes hands, how it ends —
is what needed sign-off, and now has it.

---

## 1. The state machine `[D-01]`

**Recommendation.** One linear machine, shared by nation wars and independent-town wars,
with every transition persisted and replayable:

```
PEACE
  └─ DECLARED        declaration accepted, defender notified, terms locked
      └─ PREPARATION protection intact, no combat, both sides may fortify or capitulate
          └─ ACTIVE  combat windows open; capture possible
              ├─ SURRENDER  one side capitulates → TERMS
              ├─ VICTORY    a victory condition met → TERMS
              └─ TIMEOUT    war clock expires → TERMS (scored draw or points win)
                  └─ TERMS      land/tribute settlement applied atomically
                      └─ OCCUPATION (optional, time-boxed)
                          └─ RECOVERY  shields granted, regeneration queued
                              └─ PEACE
```

Every transition is idempotent and keyed, so a server restart or a cross-server replay
cannot double-apply a settlement.

**Alternatives weighed.** A parallel per-front machine (more expressive, much harder to
reason about across a Velocity network). A machine without `TERMS` that applies effects at
the moment of victory (simpler, but makes land transfer non-atomic — rejected).

**Decision needed:** accept this shape, or name the states you want added or removed.

---

## 2. Declaration `[D-02]`

**Recommendation.**

| Rule | Default |
|---|---|
| Who may declare | Nation leader, or the mayor of an independent town (a town in no nation) |
| Target | A nation, or an independent town. A town inside a nation cannot be targeted separately — its nation is the belligerent |
| Minimum size | Both sides need ≥ 3 residents and ≥ 1 claim |
| Cost | A configurable declaration fee from the civic bank, refunded if the defender is found ineligible |
| Cooldown | No new declaration against the same target for 7 days after a war ends |
| Consent | **Not required.** A war may be declared unilaterally, which is why preparation exists |
| Blocked while | Either side is shielded, occupied, already at war, or inside a preparation window |
| Alliance drag-in | Allies are **invited**, not conscripted. Mutual-defence treaties (Phase 6) may auto-accept |

**Open question `Q-02a`:** should an admin be able to require approval for every
declaration on a peaceful-leaning server? Recommendation: yes, a config flag
`war.declaration.requires-admin-approval`, default `false`.

---

## 3. Preparation `[D-03]`

**Recommendation.** 24 real-time hours by default, configurable, minimum 1 hour.

During preparation: territory protection is **fully intact**, no PvP change, no claiming
inside the opponent's territory, both sides may recruit, fortify, buy shields *for other
wars only*, or capitulate without penalty. The defender may pay a configurable
"appeasement" to cancel, if the attacker's terms allowed it.

Rationale: preparation exists so a war cannot be used to catch an offline server asleep.
Its length is the single biggest lever on how punishing war feels; 24 h suits a community
server, 2 h suits a hardcore one.

---

## 4. Combat windows, protection and raiding `[D-04]`

**Recommendation.**

- **Scheduled windows.** Combat is only live inside configured windows (default: two
  2-hour windows per day, server-timezone). Outside a window the war stays `ACTIVE` but
  territory protection is fully restored. This is what stops 3 a.m. raiding.
- **Inside a window, in a belligerent's claims:** PvP forced on; block break/place
  **disabled** except on designated objectives; container access **disabled**;
  explosions **disabled**. War is fought over objectives, not by griefing.
- **Outside belligerent territory:** nothing changes.
- **Wilderness:** unchanged.
- **Non-belligerents** are never affected — no forced PvP, no protection loss, including
  for allies who declined to join.

**Alternative weighed:** full raiding (break/place/containers enabled in enemy claims).
Far more destructive, and it interacts badly with land regeneration and RiftShop/
RiftSpawners ownership. Not recommended as a default, but can be a config profile.

**Open question `Q-04a`:** do you want a `war.profile: objectives | raiding` switch, with
`objectives` default? Recommendation: yes.

---

## 5. Capture and scoring `[D-05]`

**Recommendation.** Capture flags, not block-breaking.

- An attacker places a **capture flag** on an eligible enemy claim during a combat window.
  Eligible = adjacent to the front (a claim bordering territory the attacker already holds
  or wilderness), never the homeblock until every other claim has fallen.
- The flag must be held for a configurable duration (default 3 minutes) with at least one
  attacker within range and no defender contesting.
- Successful capture scores points and marks the claim **contested**, not transferred —
  transfer happens at `TERMS` only.
- Points also accrue for kills within war zones, objective holds and successful defences.
  Kills are validated by **RiftPvP**, so combat-logging and self-kill farming are already
  handled.
- The homeblock is worth a large point total and its capture is a victory condition.

**Open question `Q-05a`:** should defenders be able to *re-capture* a contested claim
during the same war? Recommendation: yes, at a reduced point value, so a war is not
decided by the first window.

---

## 6. Settlement `[D-06]`

Applied atomically at `TERMS`, in one transaction, with an idempotency key.

**Recommendation.**

| Term | Default |
|---|---|
| Land transfer cap | The lesser of 25% of the loser's claims or the number actually captured. Hard cap, always |
| Homeblock | **Never transferred.** A town always keeps its homeblock and cannot be erased by war |
| Minimum viability | A town cannot be reduced below a configured minimum claim count |
| Tribute | A percentage of the loser's civic bank, capped, paid in the loser's default currency — **never converted** |
| Bankruptcy | War cannot push a town into bankruptcy; tribute is capped at the available balance |
| Nation dissolution | War never dissolves a nation. Towns may be forced to leave, not deleted |
| Player property | Personal plots, shops and spawners inside transferred claims keep their owner; only the *territory* changes hands. Freeze-and-settle rules from RiftShop/RiftSpawners apply |

Rationale: the cap and the homeblock rule exist so a losing player still has a game to
come back to the next day. Servers that want total conquest can raise the cap; they cannot
accidentally get it.

---

## 7. Occupation and recovery `[D-07]`

**Recommendation.**

- Occupation is **optional and time-boxed** (default 7 days, configurable, may be disabled
  entirely).
- An occupied town keeps its identity, bank and residents. The occupier gains a
  configurable tax share and a veto on the occupied town's claiming.
- Occupation ends on its timer, by the occupier's release, by a paid buy-out from the
  occupied town's bank, or by a third party liberating it in a later war.
- Occupation **never** transfers the civic bank, deletes residents, or changes roles.
- On release, the town enters `RECOVERY`: a free war shield (default 3 days), regeneration
  of any war-damaged terrain, and immunity from new declarations.

---

## 8. War shields `[D-08]`

Shields are mechanical and low-risk; they are listed here because they interact with the
state machine.

| Rule | Default |
|---|---|
| New-town shield | 7 days, automatic, free |
| Post-war recovery shield | 3 days, automatic, free |
| Purchasable shield | Only if the administrator enables it. Paid from the civic bank |
| Scope | Town or nation. A nation shield covers member towns; a town shield does not cover its nation |
| Extension | From the treasury, at an **escalating** cost — each extension costs more than the last |
| Total cap | A hard cap on cumulative shielded days per rolling period |
| Cooldown | Between purchases, independent of the cap |
| **Cannot activate** | During war, occupation, an accepted declaration, or preparation |
| Anti-hopping | Leaving and rejoining a nation does not refresh a shield; the shield follows the organisation UUID |
| Anti-recreation | Disbanding and recreating a town does not grant a new-town shield — keyed on the founder set and the previous organisation's ruin record |
| Anti-stacking | Shields never stack; a new one extends, it does not multiply |
| **Hostile action breaks it** | If a shielded organisation declares war, captures, or attacks a belligerent, its own shield ends immediately |
| Visibility | Status shown in commands, GUI, Bedrock forms, maps, PAPI, chat and logs — a shield is never a hidden advantage |

---

## 9. Cross-server behaviour `[D-09]`

**Recommendation.** War state is authoritative in MariaDB and guarded by an advisory lock:
exactly one backend applies any given transition. Combat windows are evaluated from
persisted UTC instants, never from a per-server clock offset. On SQLite, war is
single-server only and says so at startup.

Every transition writes an outbox row, so Discord announcements and cross-server notices
are exactly-once even if a backend dies mid-transition.

---

## 10. Explicitly deferred

Not in the first war release, to keep the state machine reviewable: supply lines and field
camps, war logistics, naval or air fronts, mercenaries, war weariness, multi-front
simultaneous wars against different opponents, and permanent town deletion by conquest.

---

## Decision summary — all approved 2026-08-09

| ID | Subject | Approved position |
|---|---|---|
| D-01 | State machine shape | Linear, with `TERMS` before effects ✅ |
| D-02 | Declaration rules | Unilateral, gated by size/cost/cooldown ✅ |
| Q-02a | Optional admin approval flag | `war.declaration.requires-admin-approval`, default `false` ✅ |
| D-03 | Preparation | 24 h default, protection intact ✅ |
| D-04 | Combat windows and protection | Scheduled windows, objectives not griefing ✅ |
| Q-04a | `objectives \| raiding` profile switch | Present, `objectives` default ✅ |
| D-05 | Capture and scoring | Capture flags, RiftPvP-validated kills ✅ |
| Q-05a | Defender re-capture | Permitted, reduced value ✅ |
| D-06 | Settlement | 25% cap, homeblock never transfers ✅ |
| D-07 | Occupation and recovery | Optional, 7 days, bank untouched ✅ |
| D-08 | Shields | As tabled ✅ |
| D-09 | Cross-server | MariaDB authoritative, advisory-locked ✅ |

### What approval commits Phase 4 to

Four of these are load-bearing for the schema, and are the ones to revisit first if
anything reads wrong in play:

- **D-01** puts every effect at `TERMS`, so land transfer and tribute are one atomic,
  idempotent settlement rather than a trickle of mutations during combat.
- **D-05** makes a captured claim *contested*, not transferred, which means the claim table
  needs a contested marker rather than a second ownership column.
- **D-06** caps transfer at 25% and makes the homeblock untransferable, so a town always has
  something to come back to. This is the decision most likely to feel wrong to an
  aggressive playerbase, and the easiest to raise as a config ceiling later.
- **D-07** keeps the civic bank, residents and roles untouched by occupation, which keeps
  occupation out of the economy schema entirely.
