# Security and Privacy

Applies to every plugin in the ecosystem. Where this document and a feature request
disagree, this document wins.

---

## 1. The line between gameplay espionage and moderation

These are different systems with different owners, different data and different
authorisation. Conflating them is how a game mechanic becomes a privacy incident.

| | Gameplay espionage | Moderation SocialSpy |
|---|---|---|
| Owner | RiftWars (`RW-ESPIONAGE`) | **RiftPunishments** |
| Audience | Players | Authorised staff |
| Data | **Generated intelligence only** | Real messages, under authorisation |
| Authorisation | In-game role and mission | Staff permission, session-scoped |
| Audited by | RiftLogger (mission outcomes) | RiftLogger (**every staff access**) |

### 1.1 What gameplay espionage may produce

Approximate, time-limited, generated reports: readiness bands · treasury bands · supply
status · activity level · fog-of-war map layers · patrol and defence indicators · decoy and
misinformation results · sabotage outcomes **limited to generated military assets**.

"Approximate" is load-bearing. A report says *"treasury: substantial"*, never a number that
could only have come from reading the database.

### 1.2 What gameplay espionage may never produce

Raw private messages · staff chat · IP addresses · private inventories · passwords ·
**exact live player coordinates** · anything read directly from another plugin's storage.

A spy who succeeds learns that a nation is mobilising. A spy never learns where a specific
player is standing right now.

### 1.3 Ownership boundaries, restated

**RiftChat** owns message creation, channels, routing, formatting and the live interception
hook. **RiftPunishments** owns SocialSpy sessions, staff authorisation, granular spy
permissions, cases, evidence, appeals, retention, punishment actions and staff-access
auditing. **RiftLogger** records staff access and administrative change.

RiftWars asks RiftWars' own intelligence API. It never subscribes to RiftChat's
interception hook, and no code path exists by which it could.

### 1.4 Always excluded from interception, at any authorisation level

Passwords · authentication commands · commands on the configured sensitive list ·
staff-only channels unless separately and explicitly authorised · any configured protected
input pattern.

Exclusion is enforced **before** the message reaches a spy session, not filtered afterward
— a filter that runs late has already copied the data.

## 2. Financial integrity

- Every monetary operation carries a stable idempotency key and is safe to retry.
- Escrow for war bonds, bounties and scheduled treaty payments; escrow release is itself
  idempotent.
- **Two-person approval** available for large civic transactions, configurable by threshold.
- Transaction alerts and treasury freeze available to organisation leadership.
- Balances are never summed or converted across currencies. Conversion happens only through
  an explicit RiftEco exchange quote that a human accepted.
- Administrative and suspicious transactions are excluded from leaderboards and season
  scoring.
- Full ledger retained; a total is always reconstructible from entries.

## 3. Data protection

- **No secrets in logs.** No bot tokens, webhook URLs, database passwords or API keys —
  RiftTowny never stores Discord credentials at all; VelocitySrv does.
- **No live player coordinates in public output**, including bounty listings, war feeds and
  the web portal. Bounty search regions are approximate by default.
- Scoped API keys for the portal, per-key rate limited, revocable, and read-only.
- Retention periods configurable per data class; audit records have their own, longer
  retention and are never silently pruned.
- Privacy controls let a player hide their profile, service record and organisation history
  from public surfaces without losing them.
- Personal data is never placed in a URL parameter.

## 4. Operational safety

- Permission checks at the **service boundary**, so the public API is not a way around them.
- Rate limits on claim, invite, deposit, message, application and declaration operations.
- Input validation, name normalisation and a reserved-word list on every player-supplied
  name.
- Duplicate-event protection on every cross-server and Discord path.
- Graceful degradation: a missing integration disables only what depends on it.
- Dry-run available for every destructive administrative operation.
- Backup, integrity-check and repair commands, with a preview before anything is written.
- Partial failure and shutdown are handled explicitly: in-flight work either completes or is
  replayable from the outbox.

## 5. Permanently excluded

Not a backlog. These are never built.

| Excluded | Why |
|---|---|
| Real-money cash-out | Turns the economy into a financial product |
| Cryptocurrency or NFT ownership | Same, plus custody risk |
| Donor-only competitive combat advantage | Pay-to-win; boosters are server-wide by design |
| Unrestricted griefing | War is fought over objectives, not by destroying builds |
| Permanent forced imprisonment | No mechanic may end a player's ability to play |
| Public exact live player tracking | Harassment vector |
| Gameplay access to raw private messages | §1 |
| Automatic irreversible claim or asset deletion | Data loss must always require a human |
| Direct combat power purchased with organisation wealth | Wealth buys logistics, never damage |
| Arbitrary unsandboxed scripts | Remote code execution by configuration |
| Automatic era advancement without approval | An operator must always choose |

## 6. Accessibility

Not a security concern, but the same class of requirement — non-negotiable and easy to skip.

Colourblind-safe themes · plain-text alternatives to every colour-encoded state · full
keyboard-reachable command equivalents for every GUI action · Bedrock form parity ·
localisation with per-locale message bundles · local timezone formatting on every
schedule surface.
