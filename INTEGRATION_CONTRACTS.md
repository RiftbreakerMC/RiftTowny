# RiftTowny — Integration Contracts

Every optional dependency is listed here with the **verified** state of its real API. A
contract is only marked `VERIFIED` if the named types and method signatures were read from
that plugin's source in this workspace. Nothing is assumed, and nothing is described as
working until an adapter for it has been built and tested.

Sources inspected: local clones under `D:\RiftBreakerSmoke\work`, fast-forwarded to
`origin` on 2026-08-09. Two clones held uncommitted local work and were left untouched —
see §4.

| State | Meaning |
|---|---|
| `VERIFIED` | Real types and signatures read from source. An adapter can be written against them. |
| `PARTIAL` | Plugin exists and some surface is verified; the specific capability RiftTowny needs is not present in that surface. |
| `BLOCKED` | The capability RiftTowny needs does not exist upstream. The required contract is defined below and must be built there first. |
| `NOT INSPECTED` | Third-party plugin, no local source; adapter will be written against published API and marked unverified until tested on a server. |

---

## 1. Headline finding: the ecosystem is already bound to Towny

A sweep for `com.palmergames`, `TownyAPI` and `getPlugin("Towny")` found **178 occurrences
across 54 files in 12 sibling repositories**:

| Repository | Towny-coupled files |
|---|---|
| VelocitySrv | `backend/hook/TownyHook`, `backend/listener/TownyListener`, `TownyMilestoneListener`, `SiegeWarListener` |
| RiftShop | `paper/towny/` — 11 classes (`TownyOwnership`, `TownyMembership`, `TownTreasuries`, `TerritorialTax`, `TerritorialRegions`, `EmbargoCommand`, `LevyCommand`, …) |
| RiftEco | `platform/towny/TownyLifecycleBridge`, `platform/placeholder/TownyPlaceholderResolver` |
| RiftEvents | `service/TownyProtectionService`, `listener/TownyClaimListener` |
| RiftEssentials | `Rtp/TownyClaimGuard` |
| RiftPVP | `integration/TownyHook` |
| RiftCosmetics | `wardrobe/ReflectiveTownyWardrobeTerritoryService`, `integration/TownySiegeRestrictionService` |
| RiftPunishments | `paper/TownyPermanentBanGovernanceHandler` |
| RiftLogger | `model/TownyRecord` |
| RiftCore | `integration/ServerPolicyService`, `TeleportSafetyService`, `privacy/PrivacyAccessService` |
| RiftBoosters | `rules/PaperBoosterRuleContextFactory`, `BoosterRuleEngine` |
| ExperienceManager2 | referenced in tests |

Because RiftTowny cannot coexist with Towny (§SPECIFICATION 1.1), **all of these go
dormant the moment RiftTowny is installed.**

**Agreed remedy (2026-08-09): native adapters.** RiftTowny publishes `rifttowny-api`;
each sibling gains a RiftTowny hook alongside its existing Towny hook, selected by
capability detection so neither breaks. One tracked PR per repository, staged after the
RiftTowny core is stable. A `com.palmergames` emulation shim was considered and rejected:
it squats another project's package namespace and undermines the clean-room position.

### 1.1 Two integrations need no upstream change at all

Both RiftEco and RiftLogger already model Towny governments with **Towny-free types**.
RiftTowny can call them today.

- `net.riftbreaker.eco.api.TownyGovernmentKey` is a plain record of
  `(TownyGovernmentType, UUID id, UUID accountId)` — no Towny import anywhere in the type
  or its service.
- `mc.riftbreaker.riftlogger.model.TownyRecord` is a plain record whose `source` field
  merely *defaults* to `"Towny"` and can be set to `"RiftTowny"`.

This is a significant saving: civic banking and civic audit work without touching either
repository.

---

## 2. Per-integration contracts

### 2.1 RiftEco — economy — `VERIFIED`

`net.riftbreaker:rifteco` · package `net.riftbreaker.eco.api` · 99 public API types.

Types RiftTowny consumes:

| Type | Use |
|---|---|
| `RiftEcoProvider` / `RiftEcoService` | Service acquisition |
| `RiftEcoTownyService` | **Civic banking, verbatim.** See below |
| `RiftEcoBankService`, `BankKey`, `BankRole`, `BankSnapshot`, `BankMutationResult` | Bank membership and withdrawal authority |
| `Money`, `CurrencyKey`, `CurrencyDefinition` | Multi-currency amounts; RiftTowny persists `CurrencyKey` IDs, never display names |
| `AccountRef`, `AccountType.SHARED` | The civic account identity |
| `TransactionReceipt`, `TransactionStatus`, `TransactionKind` | History and idempotency |
| `RiftEcoFeeService`, `FeeQuote`, `FeeRule`, `FeeDestination` | Claim costs, war costs, shield costs, tribute |
| `RiftEcoNetworkService`, `NetworkMutationRequest/Response` | Cross-server safe mutation |
| `RiftEcoEventService`, `RiftEcoEventListener` | Reacting to balance changes |

`RiftEcoTownyService` is an exact match for the brief's civic-bank requirements and is
already asynchronous:

```java
AccountRef treasuryAccount(TownyGovernmentKey key);
CompletableFuture<TownyGovernmentSnapshot> synchronize(TownyGovernmentKey key, String displayName, UUID leaderId);
CompletableFuture<TownyGovernmentSnapshot> rename(TownyGovernmentKey key, String displayName);
CompletableFuture<TownyGovernmentSnapshot> transferLeadership(TownyGovernmentKey key, UUID leaderId);
CompletableFuture<TownyLifecycleResult> disband(TownyGovernmentKey key, UUID leaderId, String cause, boolean bankruptcy);
CompletableFuture<Optional<TownyGovernmentSnapshot>> find(TownyGovernmentKey key);
CompletableFuture<List<TownyGovernmentSnapshot>> activeGovernments();
```

RiftTowny's contract: pass its own town/nation UUID as `id` and a **separate, stable**
`accountId` so a future ID policy change cannot orphan a treasury. Rename and leadership
transfer call the matching methods so the account survives both, satisfying the
"leadership transfer and renaming must preserve stable organization IDs and bank accounts"
requirement.

**Conflict to resolve before Phase 2 ships:** RiftEco's `TownyLifecycleBridge` still
listens for real Towny events. With RiftTowny installed it attaches to nothing and idles
harmlessly, but its startup log line claims a Towny integration. A one-line PR to RiftEco
should make that message conditional. Non-blocking.

Fallback when RiftEco is absent: **VaultUnlocked**, single-currency, no fee quotes. All
multi-currency features report `UNSUPPORTED` rather than silently collapsing currencies.

### 2.2 RiftLogger — audit — `VERIFIED`

`mc.riftbreaker:riftlogger` · `mc.riftbreaker.riftlogger.service.AsyncRiftLoggerService`.

```java
CompletableFuture<Void> logTowny(TownyRecord record);
CompletableFuture<Void> logTownyIdempotent(String sourceEventId, TownyRecord record);
```

`TownyRecord(entityType, action, actorId, actorName, subjectId, subjectName, entityName,
previousValue, newValue, source, metadata)` — RiftTowny sets `source = "RiftTowny"`.

RiftTowny uses `logTownyIdempotent` with its outbox event ID for anything replayable, so a
retried outbox delivery cannot duplicate an audit row.

**RiftLogger is the only audit integration.** CoreProtect was dropped on 2026-08-10, and
per-block history moves here.

### 2.2.1 Per-block change history — **implemented upstream 2026-08-10**

RiftLogger recorded events but not block changes, so it could not replace CoreProtect. That
gap was closed in `Riftlogger@4e91300` — schema migration 3, 14 tests against real SQLite.
**The API below is verified against that commit, not proposed.**

```java
// mc.riftbreaker.riftlogger.service.AsyncRiftLoggerService
CompletableFuture<Void> logBlockChange(BlockChangeRecord record);
CompletableFuture<Void> logBlockChanges(List<BlockChangeRecord> records);   // batched
CompletableFuture<List<StoredBlockChange>> queryBlocks(BlockQuery query);
CompletableFuture<RollbackResult> rollbackBlocks(
        BlockQuery query, UUID performedBy, String performedByName, boolean dryRun);
CompletableFuture<Integer> pruneBlockChangesBefore(Instant cutoff);
boolean supportsBlockHistory();

// mc.riftbreaker.riftlogger.model
record BlockChangeRecord(Instant occurredAt, BlockAction action, UUID actorId, String actorName,
        UUID worldId, int x, int y, int z, String previousBlock, String newBlock,
        String blockData, String containerContents, String source, Map<String, String> metadata)
enum BlockAction { PLACE, BREAK, MODIFY, EXPLODE, BURN, FLOW, PISTON, REGENERATE,
                   CONTAINER_ACCESS, INTERACT }   // isRollbackable() false for the last two

// mc.riftbreaker.riftlogger.query
record BlockQuery(UUID worldId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
        Instant since, Instant until, UUID actorId, BlockAction action, int limit)
record RollbackResult(int matched, List<BlockRestoration> restorations, boolean dryRun,
        UUID performedBy, Instant performedAt)
```

**One correction to what this section originally specified.** It implied RiftLogger would
apply the rollback. It does not, and should not: placing blocks safely means region
scheduling on Folia, rate limiting, and knowing which blocks belong to a shop or a spawner —
all of which live in the plugin that owns the land. `rollbackBlocks` computes the plan, marks
the rows it accounted for, and returns `List<BlockRestoration>` for **RiftTowny** to apply
through its own scheduler.

Semantics RiftTowny's adapter must respect:

- **Batch, never per block.** A creeper is dozens of rows and a regeneration sweep is
  thousands.
- **The plan restores the oldest state in the window**, so undoing one incident is one
  rollback rather than three.
- **Rows already rolled back are skipped**, so undoing twice cannot overwrite a legitimate
  rebuild.
- **`dryRun` first** for anything an operator triggers, mirroring the preview rule RiftTowny
  applies to claims.
- **Block writes have no JSONL fallback.** If the database is down, the write fails loudly
  rather than spilling to a file that cannot be queried by area or rolled back.

**Still to do in RiftTowny:** the adapter itself, the listeners that feed it, and wiring
regeneration to record `REGENERATE` rows. Until that lands `AUDIT_BLOCK_HISTORY` stays
`BLOCKED` at startup — the upstream API exists, RiftTowny does not yet call it.

**Gap:** `TownyEntityType` and `TownyAction` are fixed enums upstream. RiftTowny adds
concepts Towny never had (areas, elections, shields, occupation). Actions with no upstream
enum constant are logged under the nearest existing constant with the precise action in
`metadata`, until a PR widens the enums. Documented rather than silently lossy.

### 2.3 RiftPvP — combat — `VERIFIED`

`net.riftbreaker:riftpvp` · `net.riftbreaker.pvp.api`.

```java
public interface RiftPVPService {
    boolean isTagged(UUID playerId);
    Duration remaining(UUID playerId);
    Optional<UUID> opponent(UUID playerId);
    void tag(UUID firstPlayer, UUID secondPlayer, Duration duration);
    void untag(UUID playerId);
    boolean isRestricted(UUID playerId);
}
```

Also present: `RiftAntiCheatBridge`, `AntiCheatExemptionReason`, and the events
`RiftCombatTagEvent`, `RiftCombatLogEvent`.

**RiftTowny → RiftPvP:** listens for `RiftCombatTagEvent` and removes RiftTowny-managed
flight, entering Soft Landing.

**RiftPvP → RiftTowny (needs a PR):** `net.riftbreaker.pvp.integration.TownyHook` exposes
`allowsTag(Player attacker, Player victim)` and `isWilderness(Location)` by reflecting on
`TownyAPI`. RiftTowny supplies the same two answers natively:

```java
boolean pvpAllowedBetween(UUID attacker, UUID victim, WorldPosition at);
boolean isWilderness(WorldPosition at);
```

Anti-cheat exemption during RiftTowny flight goes through `RiftAntiCheatBridge`.

### 2.4 RiftBoosters — flight boosters — `VERIFIED`

`com.riftbreaker:riftboosters` · `com.riftbreaker.boosters.api`: `RiftBoosterService`,
`BoosterModifierService`, `BoosterRewardService`, `RiftBoosterControlPlaneService`,
`RiftBoosterIntegrations`, `BoosterQuery`, `event.BoosterLifecycleEvent`, and
`api.network.BoosterNetworkBridge` / `BoosterNetworkSnapshot` for cross-server state.

Ownership is as the brief states: **RiftBoosters owns the authoritative timer**;
RiftTowny owns eligibility. RiftTowny subscribes to `BoosterLifecycleEvent` and never
computes remaining time itself — it reads it from the booster snapshot, which is
wall-clock persisted, so rejoin and server transfer restore correctly.

**Needs verification on a server:** whether `BoosterNetworkSnapshot` propagates through
VelocitySrv for a *global* community booster, or only per-backend. Until proven, the
community booster is documented as network-wide on MariaDB and single-backend on SQLite,
and `/rifttowny status` reports which mode is actually active.

### 2.5 RiftChat — chat — `VERIFIED`

`net.riftbreaker:riftchat` · `net.riftbreaker.chat.api`: `RiftChatService`,
`RiftRichChatService`, `RiftChatCompatibilityService`, `ChatPresentationRequest`,
`PresentationContextKeys`.

```java
Component render(ChatPresentationRequest request);
void send(Player viewer, ChatPresentationRequest request);
```

Read at `0.1.0-rc.1` and installed locally, so the adapter compiles against the real API.

**The division is settled by RiftChat's own javadoc**, not inferred:

> Immutable message presentation input supplied by RiftCore **after it has selected the
> channel and recipients**. RiftChat uses this data for rendering only. […] callers must
> never rely on RiftChat to validate routing or privacy.

So RiftTowny selects recipients — it is the only thing that knows who is in a town — and
RiftChat renders. RiftTowny does **not** build a second chat formatter.

Three findings that change what has to be built, all verified by reading the source:

1. **`RiftChatService.Channel` already declares `TOWN` and `NATION`.** Routing town and
   nation chat needs no upstream change at all. `/tc` and `/nc` are ours to write today.
2. **There is no `ALLY` constant.** `/ac` therefore needs either a RiftChat enum addition or
   a deliberate reuse of `PARTY`. It is blocked twice over regardless, because
   `RT-MOD-DIPLOMACY` does not exist and RiftTowny has no allies to send to. Not built, and
   not faked.
3. **`TownyPresentationService` is unreachable on a RiftTowny server**, and the previously
   recorded PR target is unnecessary. It reflects on
   `com.palmergames.bukkit.towny.TownyAPI` and its factory returns `null` unless
   `Bukkit.getPluginManager().isPluginEnabled("Towny")` — and RiftTowny refuses to start
   beside Towny. Nothing in the `api` package offers a provider seam to replace it.

   It does not need one. `PlaceholderPresentationService` resolves operator-configured
   `%…%` expressions through PlaceholderAPI on a timer, so once RiftTowny's expansion is
   registered (§2.13), RiftChat shows town and nation context in **any** channel with no
   RiftChat code change — an operator configures the expression and it works. The
   placeholder surface *is* the chat integration.

### 2.6 VelocitySrv — network and Discord — `PARTIAL` / `BLOCKED`

`com.velocitysrv:riftcore-parent`. Shared API in `com.riftbreaker.core.api`
(`RiftCoreApi`, `RiftCoreProvider`, `RiftCoreChannels`, `MessagingService`,
`PresenceService`, `PartyService`).

- **Cross-server transport — `VERIFIED`.** `RiftCoreChannels` plus the backend bridge in
  `com.velocitysrv.backend.bridge` is the transport for RiftTowny's outbox.
- **Discord announcements — `VERIFIED`.** `velocity.discord.AnnouncementService`,
  `AlertService`, `WebhookRelay`, `DiscordMessageRenderer`. RiftTowny stores **no bot or
  webhook credentials**; it emits outbox rows and VelocitySrv renders them.
- **Backend Towny hook — needs a PR.** `backend/hook/TownyHook` exposes static
  `townBalance`, `townDetails`, `nationDetails`, `topTowns(limit, metric)`,
  `topNations(limit, metric)` returning its own `TownInfo` / `TownDetails` /
  `NationDetails` / `BoardEntry` records. RiftTowny provides identical data natively.

**`BLOCKED` — per-organisation Discord channel provisioning.** A repository-wide search
for `createTextChannel`, `createChannel`, `createCategory` and `GuildChannel` in
VelocitySrv's `src/main` returned **zero matches**. There is no channel-creation API.

`/town discord create` and `/nation discord create` therefore **cannot be implemented**
until VelocitySrv gains the following. No method here has been written yet, and RiftTowny
will not pretend otherwise:

```java
public interface DiscordChannelProvisioningService {
    CompletableFuture<ProvisionedChannel> createOrganisationChannel(ChannelRequest request);
    CompletableFuture<Void> syncMembers(String channelId, Collection<UUID> linkedMembers);
    CompletableFuture<Void> archiveChannel(String channelId, String reason);
    CompletableFuture<Optional<ProvisionedChannel>> findChannel(String correlationId);
}

public record ChannelRequest(
        String correlationId,     // RiftTowny organisation UUID; makes creation idempotent
        String displayName,
        ChannelScope scope,       // TOWN or NATION
        boolean privateChannel,
        Collection<UUID> initialMembers) { }

public record ProvisionedChannel(String channelId, String inviteUrl, Instant createdAt) { }
```

Until that exists the commands are registered but respond with an explicit
"not available on this network" message, and `/rifttowny status` reports the capability as
`BLOCKED`.

### 2.7 RiftShop — shops — `VERIFIED` (surface), PR required

`mc.riftbreaker.shop` · modules `riftshop-api`, `-core`, `-storage-mariadb`, `-paper`.

RiftShop stays the transaction and listing engine. RiftTowny supplies territory and civic
rules: shop ownership by player/town/nation, civic-bank ownership, market areas, access
flags, sales tax, and a safe freeze-and-settle on disband.

The 11-class `mc.riftbreaker.shop.paper.towny` package is the PR target. Its concerns map
one-to-one onto RiftTowny concepts: `TownyOwnership` → area ownership,
`TownyMembership` → resident/role lookup, `TownTreasuries` → civic bank,
`TerritorialTax`/`LevyCommand` → sales tax, `TerritorialRegions` → claim lookup,
`EmbargoCommand` → diplomacy, `ContrabandCommand`/`SpecialiseCommand` → town policy.

### 2.8 RiftSpawners — spawners — `VERIFIED` (surface), PR required

Repository `Riftspawners` ships **SmartSpawner** (`github.nighter.smartspawner`,
30 API files under `api/`, `api/events/`, `api/gui/`). It is a third-party codebase —
**check its licence before contributing changes upstream**; RiftTowny's own adapter is
written against its public API only.

RiftSpawners remains the spawner authority. RiftTowny supplies place/break/upgrade/link/
collect permissions, ownership (player/town/nation), civic-bank purchases, caps, dedicated
spawner areas, and war/unclaim behaviour. **Regeneration must never re-place or delete a
spawner from a snapshot** — spawner blocks are handed to RiftSpawners.

### 2.9 RiftEssentials — RTP — `VERIFIED`, smallest contract in the set

`com.riftbreaker:riftessentials`. It **owns `/rtp`** (registered in `commands.yml`, with
`Rtp/RandomTeleportService`, `RtpSettings`, `RtpConfirmMenu`).

RiftTowny does not register `/rtp` or `/wild`. It implements RiftEssentials' existing SPI:

```java
package net.riftbreaker.mocha.riftbreakerEssentials.Rtp;
public interface ClaimGuard {
    String pluginName();
    boolean isClaimed(Location location);
}
```

`ClaimGuards.detect()` already builds its guard list from `HookManager.has(pluginName)`
and tolerates a missing plugin. The PR is two lines plus a `RiftTownyClaimGuard` class.

RiftTowny additionally exposes its full safe-destination API so RiftEssentials can adopt
the stricter dry-land rules if it wants them; it does not require that to work.

### 2.10 RiftEvents — events, bounties, supply drops — `VERIFIED` (surface), PR required

Repository `RiftEvents` · package `com.riftbreakermc.riftoutposts` (the package name lags
the repository name). Relevant classes: `service/TownyProtectionService`,
`listener/TownyClaimListener`, `service/OutpostManager`,
`service/VelocitySrvAnnouncementService`, `service/EconomyCoreService`.

RiftEvents owns bounty lifecycle, objectives, expiry, announcements, rewards and
leaderboards, and it owns supply-drop placement. RiftTowny validates candidate locations
(wilderness, flags, war state, distance, claims, regeneration, protected areas) and
validates civic spending authority and diplomacy for town- or nation-funded bounties.
RiftEco escrows; RiftPvP validates kills; RiftLogger records transitions.

Events RiftTowny consumes/emits: `BountyCreated`, `BountyIncreased`, `BountyClaimed`,
`BountyExpired`, `BountyCancelled`.

### 2.11 RiftCore — shared services — `VERIFIED`

`net.riftbreaker:riftcore` · `net.riftbreaker.core.api` (`RiftCoreApi`,
`RiftCoreProvider`, `RiftIntegrationService`, `RiftPlayerSnapshot`, `RiftSnapshotService`)
and `net.riftbreaker.core.integration` (`IntegrationRegistry`, `CoreAdapterRegistry`,
`ServerPolicyService`, `TeleportSafetyService`, `RiftChatGateway`,
`McMmoPartyExperienceBridge`, `RiftPlaceholderExpansion`).

`net.riftbreaker.core.gui` is the org's shared menu framework — RiftTowny's Java GUIs
build on it rather than inventing a fourth menu system. Bedrock users get Floodgate forms
for typed input.

`TeleportSafetyService` and `ServerPolicyService` both currently consult Towny; both are
PR targets.

### 2.7 RiftEco — player wallets — `VERIFIED` (2026-08-13)

Cloned to `C:\Users\zws11\Coding\RiftEco` at `d81ee73` and installed to the local Maven
repository as `net.riftbreaker:rifteco:0.1.0-SNAPSHOT`. The adapter is compiled against the
real API — `provided` scope, so it is **not** shaded into `RiftTowny.jar`; two copies of
`RiftEcoService` on one classpath is a `ClassCastException` on the server that has the real
one. A test asserts the jar contains no `net/riftbreaker/eco/` classes.

**What was read, and what is used** (`net.riftbreaker.eco.api`):

| Type | Member | Used for |
|---|---|---|
| `RiftEcoProvider` | `find()` → `Optional<RiftEcoService>` | Locating the service through Bukkit's services manager |
| `RiftEcoService` | `balance(AccountRef, CurrencyKey)` | `PlayerWallet.balanceOf` |
| `RiftEcoService` | `withdraw(AccountRef, Money, String)` | `PlayerWallet.take` |
| `RiftEcoService` | `deposit(AccountRef, Money, String)` | `PlayerWallet.give` |
| `RiftEcoService` | `defaultCurrency()` → `CurrencyDefinition` | The currency organisations bank in, and the binding proof |
| `AccountRef` | `player(UUID)` | Addressing a player's account |
| `Money` | `(CurrencyKey, BigDecimal)` | Amounts. Both sides are `BigDecimal`, so nothing rounds |
| `TransactionReceipt` | `successful()` | Whether money actually moved |

**Deliberately not used.** RiftEco has `AccountType.TOWN`, a bank subsystem, fees, rewards,
an ore exchange and its own `RiftEcoTownyService` bridge. None is touched. A town's treasury
stays in RiftTowny's ledger: putting it in another plugin's storage would put the balance
outside our transaction, give the ledger two sources of truth, and make disbanding a town
depend on a second plugin agreeing. This seam is one wallet, one direction at a time.

**Without RiftEco installed**, `PlayerWallet.absent()` takes over and refuses every transfer;
the civic ledger is unaffected.

**What is built and does not need RiftEco.** The civic ledger is entirely RiftTowny's:
`rt_organisation_balance` and `rt_bank_ledger`, balances, history, and every rule about who
may move what. A town has a treasury on a server with no economy plugin at all. Taxes,
upkeep, claim costs and the ruin reclaim price will all move money inside that ledger.

**Why the seam exists at all.** A player's balance belongs to the economy plugin, and
RiftTowny will not keep a second one: a server with two answers to "how much money do I have"
has a bug it cannot fix. So everything that moves money between a person and an organisation
goes through one interface, and nothing else in RiftTowny knows what an economy plugin is.

**The contract any adapter must satisfy** — `net.riftbreaker.rifttowny.domain.bank.PlayerWallet`:

| Method | Required behaviour |
|---|---|
| `available()` | `true` only when the provider is actually usable |
| `currency()` | The currency organisations bank in. Must be stable for the life of the server; a change is a migration, not a config reload |
| `balanceOf(ResidentId)` | The player's balance, or empty if the provider cannot say. Must not block a server thread |
| `take(ResidentId, Money)` | `true` **only if the money was actually taken.** A `true` that did not move money creates money in the civic ledger |
| `give(ResidentId, Money)` | `true` only if the money arrived. A `false` is handled — the civic side is put back and recorded — but a wrong `true` loses it |

Two properties the adapter must preserve, because `BankService` depends on them:

1. **`take` is atomic.** RiftTowny debits the player first and credits the town second; a
   partial take would credit a town from a player who kept their money.
2. **Amounts are exact.** `Money` is `BigDecimal` at four decimal places. An adapter that
   round-trips through `double` reintroduces the error the ledger was built to avoid.

This is not a two-phase commit and does not claim to be. A crash between `take` succeeding
and the civic write committing loses that transaction; the window is one write wide, the loss
is in the safe direction, and the ledger records what did commit.

**VaultUnlocked** is the intended fallback and is still unbuilt — the same interface, a second
implementation, chosen only when RiftEco is absent.

### 2.13 PlaceholderAPI — placeholders — `VERIFIED` (2026-08-13)

`me.clip:placeholderapi:2.12.3` from `https://repo.helpch.at/releases/`, `provided` scope,
never shaded. Verified by `javap` against the resolved artifact, not from memory.

```java
public abstract class me.clip.placeholderapi.expansion.PlaceholderExpansion
        extends me.clip.placeholderapi.PlaceholderHook {
    public abstract String getIdentifier();
    public abstract String getAuthor();
    public abstract String getVersion();
    public boolean persist();
    public List<String> getPlaceholders();
    public boolean register();
}

public abstract class me.clip.placeholderapi.PlaceholderHook {
    public String onRequest(org.bukkit.OfflinePlayer player, String params);
    public String onPlaceholderRequest(org.bukkit.entity.Player player, String params);
}

public interface me.clip.placeholderapi.expansion.Relational {
    String onPlaceholderRequest(Player viewer, Player viewed, String params);
}
```

Four consequences that decide how the expansion is written:

- **`onRequest` is the override**, taking an `OfflinePlayer`. `onPlaceholderRequest` is the
  older `Player` form on the shared superclass. Taking an `OfflinePlayer` is the useful
  shape anyway: a scoreboard may ask about somebody who has logged out.
- **`null` and `""` mean different things.** Returning `null` means "not my placeholder" and
  PlaceholderAPI leaves the literal `%townyadvanced_foo%` in the output for a player to see.
  Returning `""` means "handled, and the answer is blank". Every name in the manifest must
  therefore return a string — *never* `null` — or an unimplemented placeholder appears in
  chat as raw markup. This is the assertion the golden test exists to make.
- **`persist()` must return `true`.** It defaults to false, which unregisters the expansion
  on `/papi reload` — the failure mode being placeholders that work until an operator
  touches PlaceholderAPI and then silently stop.
- **Relational is a separate registration**, a second expansion object implementing
  `Relational`, which is how `%rel_townyadvanced_color%` is served.

`provided` scope matters more here than usual: an expansion class shipped inside our own jar
would be loaded by our classloader while PlaceholderAPI loads its own, and registration would
fail with a `ClassCastException` naming two classes that print identically.

### 2.12 Third-party — `NOT INSPECTED`

| Plugin | Use | Note |
|---|---|---|
| ~~PlaceholderAPI~~ | — | **Inspected 2026-08-13.** Promoted to §2.13 |
| LuckPerms | Contexts for town, nation, role, relationship, war state | Contexts must be computed from cache |
| ~~CoreProtect~~ | — | **Dropped 2026-08-10.** RiftLogger owns permanent audit records, and two audit systems means two places to look and two retention policies. See §2.2 for what that costs |
| Floodgate / Geyser (Cumulus) | Bedrock forms | Absence disables forms, never a Java feature |
| BlueMap / Dynmap / squaremap | One map abstraction, three back ends | Each independent; one missing does not disable the others |
| mcMMO | Territory XP and ability modifiers | mcMMO stays the skill authority; RiftTowny only supplies multipliers within admin-defined limits |
| VaultUnlocked | Economy fallback | Used only when RiftEco is absent |
| DiscordSRV | Legacy Discord path | Only where VelocitySrv does not already cover it |

---

## 3. Adapter isolation rule

Each adapter is constructed inside its own guard and registered in the capability
registry. A `NoClassDefFoundError`, `NoSuchMethodError` or any `RuntimeException` during
adapter construction marks that one integration `FAILED` with the cause recorded, and
**nothing else is affected**. This is the pattern RiftEssentials' `ClaimGuards.tryAdd`
already uses, and it is what makes a version mismatch a degraded feature instead of a dead
server.

Startup never logs "integration active" for an integration that was not actually
resolved. `/rifttowny status` prints the registry's real state.

---

## 3A. New sibling plugins and capability interfaces (2026-08-10)

The ecosystem gains four plugins alongside RiftTowny: RiftSeasons, RiftWars,
RiftInfrastructure and RiftCivics. See [MODULE_GRAPH.md](MODULE_GRAPH.md) for the graph.

**None of their repositories exist yet.** They are `RiftbreakerMC/RiftSeasons`,
`/RiftWars`, `/RiftInfrastructure`, `/RiftCivics` when created. Nothing is written against
them today.

### 3A.1 Capability interfaces — the contract that prevents a cycle

Published from `rifttowny-api`, package
`net.riftbreaker.rifttowny.api.capability.provider`. RiftWars consumes them; RiftCivics and
RiftInfrastructure provide them. **Neither side compiles against the other.**

| Interface | Provider | Documented default when absent |
|---|---|---|
| `DiplomacyCapability` | RiftTowny basic, RiftCivics advanced | Ally / enemy / neutral only |
| `GovernmentApprovalCapability` | RiftCivics | Nation leader is the sole authority |
| `FederationCapability` | RiftCivics | Nation-vs-town wars only |
| `SanctionCapability` | RiftCivics | Tribute and occupation only |
| `PeaceConferenceCapability` | RiftCivics | Two-party surrender or timeout |
| `InfrastructureCapability` | RiftInfrastructure | No facility objectives |
| `LogisticsCapability` | RiftInfrastructure | Flat reinforcement ticket count |
| `AssetCapability` | RiftInfrastructure | Treasury-only plunder |
| `ReconstructionCapability` | RiftInfrastructure | Shield plus timer |

**The rule that makes late installation safe:** a capability is consulted at *decision
time* and its answer is written into the frozen rule snapshot. Installing a provider
mid-season therefore changes future wars, never a war already running, and never requires
migrating an existing record.

Each is registered through the existing `DefaultCapabilityRegistry`, so a provider that
fails to bind degrades exactly like any other integration.

## 3B. Authoritative repository inventory

The addendum names fifteen existing repositories. Reconciled against the actual
`RiftbreakerMC` organisation on 2026-08-10:

| Named in addendum | Actual repo | Notes |
|---|---|---|
| ExperienceManager | **`ExperienceManager2`** (default branch `master`) | Name mismatch. Two older repos also exist — `RiftbreakerXP` and `Riftbreaker-Experience-Manager`. **Which one is authoritative is unconfirmed** |
| RiftBoosters | `RiftBoosters` | verified |
| RiftbreakerRifts | **`Riftbreakerrifts`** | Ships `art.arcane:wormholes`. **Licensing must be checked before any code contact** — it depends on VolmLib and is not RiftbreakerMC-authored |
| RiftChat | `RiftChat` | verified |
| RiftCore | `RiftCore` | verified |
| RiftCosmetics | `RiftCosmetics` | verified |
| RiftEco | `RiftEco` | verified |
| RiftEssentials | `RiftEssentials` | verified |
| RiftEvents | `RiftEvents` — package is `com.riftbreakermc.riftoutposts` | package lags the repo name |
| Riftlogger | `Riftlogger` | verified |
| RiftPunishments | `RiftPunishments` | verified |
| RiftPVP | `RiftPVP` | verified |
| RiftShop | `RiftShop` | verified |
| Riftspawners | `Riftspawners` — ships **SmartSpawner** (`github.nighter`), default branch `master` | third-party; licence check before any upstream contribution |
| VelocitySrv | `VelocitySrv` | verified |

**In the organisation but absent from the inventory** — ownership unstated, so no adapter
is planned: `RiftPlots` (default branch `master`), `RiftGuide`, `EconomyCore`,
`EarthManager`, `Riftbreaker-Elytra-Manager`, `Project-RiftBreaker-Discord-Bot`.

`RiftPlots` is the one worth a decision: a plots plugin overlaps `RT-CORE-AREA` and
`RT-MOD-PROPERTY` directly.

## 3C. Missing or unresolved API paths

Nothing below is written against. Each is either documented-and-blocked or awaiting a
decision.

| # | Missing | Effect | Needed to proceed |
|---|---|---|---|
| 1 | VelocitySrv Discord channel provisioning | `RT-MOD-DISCORD-CHAN` **BLOCKED**; RiftWars per-organisation feeds limited to the global channel | Implement §2.6's contract in VelocitySrv |
| ~~1b~~ | ~~RiftLogger per-block history~~ | **Resolved 2026-08-10** — implemented in `Riftlogger@4e91300`. `AUDIT_BLOCK_HISTORY` stays `BLOCKED` only until RiftTowny writes the adapter | Write the RiftTowny adapter and listeners |
| 2 | RiftSeasons / RiftWars / RiftInfrastructure / RiftCivics repos | Those releases cannot start | Create the repositories |
| 3 | ExperienceManager identity | No progression adapter planned | Confirm which of three repos is current |
| 4 | RiftPlots ownership | `RT-CORE-AREA` scope ambiguous | Decide: absorb, integrate, or retire |
| 5 | RiftbreakerRifts licence | No dimensional-war integration | Licence review |
| 6 | Riftspawners (SmartSpawner) licence | Adapter uses the public API only; no upstream PR | Licence review before contributing |
| 7 | Anti-cheat provider | Flight exemption unverified beyond `RiftAntiCheatBridge` | Name the anti-cheat in use |
| 8 | NPC provider | `RT-MOD-NPC` has no backend | Name the NPC plugin |

## 4. Workspace note (2026-08-09)

Two reference clones hold **uncommitted local work** and were deliberately not
fast-forwarded:

- `RiftEco` — modified `pom.xml`, `RiftEcoPlugin`, `BalanceCommand`, `DailyRewardCommand`,
  `PayCommand`, `plugin.yml`; new `DailyRewardFlow`, `PaymentFlow`, `menu/`,
  `platform/bedrock/`. Five commits behind `origin/main`.
- `RiftEssentials` — modified `EssentialsCommandDispatcher`. Six commits behind
  `origin/main`.

The API surfaces quoted above were read from those working trees where they are unchanged.
Before any sibling PR is raised, that work needs committing or stashing.
