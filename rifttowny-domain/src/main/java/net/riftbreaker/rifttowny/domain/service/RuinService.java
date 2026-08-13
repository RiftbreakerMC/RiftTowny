package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.naming.NameCheck;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.Ruin;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * What happens to a town's land after the town.
 *
 * <p>Three moments, and the middle one is the feature: a town falls and leaves a ruin, somebody
 * takes the ruin on, or the window closes and the land reverts. Without the interval a disbanded
 * town's buildings become unowned ground the instant its last resident leaves, and the first player
 * to walk past owns everything in them.</p>
 *
 * <p>Reclaiming founds a <em>new</em> town on the old one's territory. It is deliberately not a
 * restoration: the residents are gone, the roles are gone, the bank account is gone, and pretending
 * otherwise would hand a stranger a treasury and a membership list. What carries over is the
 * ground, which is the part that took work to build on.</p>
 */
public final class RuinService {

    private final CivicStore store;
    private final NamePolicy namePolicy;
    private final Clock clock;
    private final TerritoryIndex territory;
    private final RuinIndex ruins;
    private final CivicCacheRefresher civic;
    private final Duration lifetime;

    /**
     * @param lifetime how long a ruin stands before its land reverts. {@link Duration#ZERO} disables
     *        ruins entirely: a town's land goes straight back to wilderness on disband, which is the
     *        behaviour a server that does not want the feature expects rather than a ruin that
     *        expires so fast nobody sees it
     */
    public RuinService(
            final CivicStore store,
            final NamePolicy namePolicy,
            final Clock clock,
            final TerritoryIndex territory,
            final RuinIndex ruins,
            final CivicCacheRefresher civic,
            final Duration lifetime
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.namePolicy = Objects.requireNonNull(namePolicy, "namePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.territory = Objects.requireNonNull(territory, "territory");
        this.ruins = Objects.requireNonNull(ruins, "ruins");
        this.civic = Objects.requireNonNull(civic, "civic");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
    }

    /** How long a ruin stands here, for a message that has to say so. */
    public Duration lifetime() {
        return lifetime;
    }

    /** The in-memory index, for listeners and for {@code /rifttowny status}. */
    public RuinIndex index() {
        return ruins;
    }

    /** Whether ruins are switched on at all. */
    public boolean enabled() {
        return !lifetime.isZero() && !lifetime.isNegative();
    }

    /**
     * Fills the index from storage.
     *
     * <p>Called once at enable and waited on, beside the territory and civic caches. A listener that
     * ran before this finished would read a ruin as wilderness, and the blocks it let through would
     * already be gone by the time the load completed.</p>
     */
    public CompletableFuture<Integer> loadIndex() {
        return store.inTransaction(transaction -> {
            final Map<Ruin, Set<ChunkKey>> standing = transaction.ruins().standing();
            ruins.replaceAll(standing);
            return standing.size();
        });
    }

    /**
     * Brings a fallen town back.
     *
     * <p><strong>A restoration, not a replacement.</strong> The town returns under its own name and
     * its own identity, with the reclaimer as its mayor and every chunk the ruin still held. That is
     * what makes the ruin window a second chance rather than a land sale: a town that fell while its
     * mayor was away is the same town when somebody picks it back up.</p>
     *
     * <p>What does not come back: its residents, its roles and its treasury. Those belonged to
     * people who have moved on, and handing a stranger a membership list would be a different
     * feature. The town is restored; its population is not.</p>
     *
     * <p>Requires the actor to belong to no town, and requires the ruin to have lain open for the
     * configured delay — without that a mayor could disband and immediately re-found, shedding
     * whatever the town had accumulated while keeping its land.</p>
     */
    public CompletableFuture<ServiceResult<Town>> reclaim(
            final ResidentId actor, final String actorName, final ChunkKey standingIn) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(standingIn, "standingIn");

        return transaction(transaction -> {
            final Ruin ruin = transaction.ruins().at(standingIn)
                    .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.NOT_A_RUIN));
            if (!ruin.stands(clock.instant())) {
                // Belt and braces: the sweep should have released it, and a ruin still holding land
                // past its window is a ruin the sweep has not reached yet.
                throw new ChangeRefusedException(ChangeDenial.RUIN_HAS_LAPSED);
            }
            if (!ruin.isReclaimableAt(clock.instant())) {
                throw new ChangeRefusedException(ChangeDenial.RUIN_NOT_YET_RECLAIMABLE);
            }

            final OrganisationName name = ruin.name();
            // The name should be free - it was released when the town fell - but a second town may
            // have taken it in the meantime, and restoring over it would be a duplicate.
            if (transaction.towns().findByName(name.normalised()).isPresent()) {
                throw new ChangeRefusedException(ChangeDenial.NAME_TAKEN);
            }

            final Resident resident = transaction.residents().find(actor)
                    .orElseGet(() -> Resident.newcomer(actor, actorName, clock.instant()));
            // The former id, not a new one. Everything written about this town before it fell - an
            // audit row, an outbox event, a war record - keeps pointing at the town it was written
            // about, and the anti-recreation rule can tell a restoration from a fresh founding.
            final TownId id = ruin.formerTown();
            final Resident joined = require(resident.joinTown(id));
            final Town town = Town.found(id, name, actor, UUID.randomUUID(), clock.instant());

            transaction.residents().save(joined);
            transaction.towns().save(town);
            transaction.roles().save(
                    RoleBook.defaultsFor(OrganisationScope.TOWN, id.value(), clock.instant()));

            // Every chunk the ruin still held comes back, and the homeblock returns to where it was.
            // Falling back to the reclaimer's own chunk covers the town that never set one, and a
            // homeblock the ruin no longer holds - which cannot happen today, but would leave a town
            // with no home if it ever did.
            final Set<ChunkKey> held = transaction.ruins().chunksOf(ruin.id());
            final ChunkKey home = ruin.home().filter(held::contains).orElse(standingIn);
            final List<Claim> taken = new ArrayList<>(held.size());
            for (final ChunkKey chunk : held) {
                final Claim claim = Claim.of(
                        chunk, id,
                        chunk.equals(home) ? ClaimKind.HOMEBLOCK : ClaimKind.ORDINARY,
                        clock.instant());
                transaction.claims().insert(claim);
                taken.add(claim);
            }
            transaction.ruins().releaseLand(ruin.id());
            transaction.ruins().update(ruin.reclaimedBy(actor, id, clock.instant()));

            transaction.publish(
                    new DomainEvent.TownFounded(id, name, actor), "reclaim:" + ruin.id());
            transaction.publish(
                    new DomainEvent.RuinReclaimed(
                            ruin.id(), ruin.formerTown(), ruin.name(), id, actor),
                    "reclaim:" + ruin.id());
            return new Reclaimed(town, ruin, taken);
        }).thenApply(result -> {
            // After the commit, never inside it. A rolled-back reclaim whose chunks had already
            // moved would leave the server protecting land for a town that does not exist.
            result.value().ifPresent(reclaimed -> {
                ruins.remove(reclaimed.ruin().id());
                reclaimed.claims().forEach(territory::put);
            });
            return result;
        }).thenCompose(result -> result.value()
                .map(reclaimed -> civic.refresh(reclaimed.town().id())
                        .thenApply(ignored -> unwrap(result)))
                .orElseGet(() -> CompletableFuture.completedFuture(unwrap(result))));
    }

    /**
     * Releases the land of every ruin whose window has closed.
     *
     * <p>Run periodically. Nothing depends on it having run promptly — a ruin past its window still
     * refuses to be reclaimed — but until it does, the land stays protected, so a server that never
     * ran it would find its map filling with monuments.</p>
     *
     * <p>The rows are kept. {@code RT-MOD-REGEN} needs to know what to restore, and
     * {@code docs/war-decisions.md} keys anti-recreation on the previous organisation's ruin record,
     * which cannot be a record if the sweep deletes it.</p>
     */
    public CompletableFuture<Integer> sweepLapsed() {
        return store.inTransaction(transaction -> {
            final List<Ruin> lapsed = transaction.ruins().lapsed(clock.instant());
            final List<Swept> swept = new ArrayList<>(lapsed.size());
            for (final Ruin ruin : lapsed) {
                final Set<ChunkKey> held = transaction.ruins().chunksOf(ruin.id());
                transaction.ruins().releaseLand(ruin.id());
                transaction.publish(
                        new DomainEvent.RuinLapsed(ruin.id(), ruin.formerTown(), ruin.name(),
                                held.size()),
                        "ruin:" + ruin.id());
                swept.add(new Swept(ruin.id(), held));
            }
            return List.copyOf(swept);
        }).thenApply(swept -> {
            swept.forEach(one -> ruins.remove(one.ruinId()));
            return swept.size();
        });
    }

    /** The ruin standing on a chunk, if one is. Answered from memory. */
    public Optional<Ruin> at(final ChunkKey chunk) {
        return ruins.at(chunk);
    }

    /**
     * Records a fall, inside a transaction that is already deleting the town.
     *
     * <p>Static and transaction-scoped rather than a method of its own, because a ruin and the town
     * it replaces have to appear and disappear together. A separate call would leave a window in
     * which the land belonged to nobody at all.</p>
     *
     * @return the ruin and the chunks it took, or empty when ruins are switched off
     */
    static Optional<Fallen> recordFall(
            final CivicTransaction transaction,
            final Town town,
            final List<Claim> claims,
            final java.time.Instant now,
            final Duration reclaimDelay,
            final Duration lifetime
    ) {
        if (lifetime.isZero() || lifetime.isNegative() || claims.isEmpty()) {
            return Optional.empty();
        }
        final ChunkKey home = claims.stream()
                .filter(claim -> claim.kind() == ClaimKind.HOMEBLOCK)
                .map(Claim::chunk)
                .findFirst()
                .orElse(null);
        final Ruin ruin = Ruin.of(
                town.id(), town.name(), town.mayor(), home, now,
                // Clamped: a delay longer than the window would be a ruin nobody could ever take on,
                // which is a configuration mistake rather than a design anybody wants.
                reclaimDelay.compareTo(lifetime) > 0 ? lifetime : reclaimDelay,
                lifetime);
        final List<ChunkKey> chunks = claims.stream().map(Claim::chunk).toList();
        transaction.ruins().save(ruin, chunks);
        transaction.publish(
                new DomainEvent.TownRuined(ruin.id(), town.id(), town.name(), chunks.size()),
                "ruin:" + ruin.id());
        return Optional.of(new Fallen(ruin, chunks));
    }

    /** A ruin and the land it took, handed out of the transaction that made it. */
    record Fallen(Ruin ruin, List<ChunkKey> chunks) {
    }

    private record Reclaimed(Town town, Ruin ruin, List<Claim> claims) {
    }

    private record Swept(UUID ruinId, Set<ChunkKey> chunks) {
    }

    private static ServiceResult<Town> unwrap(final ServiceResult<Reclaimed> result) {
        return result.value()
                .<ServiceResult<Town>>map(reclaimed -> ServiceResult.success(reclaimed.town()))
                .orElseGet(() -> result.denial()
                        .<ServiceResult<Town>>map(ServiceResult::refused)
                        .orElseGet(() -> ServiceResult.nameRejected(result.nameProblems())));
    }

    private static <T> T require(
            final net.riftbreaker.rifttowny.domain.org.Outcome<T> outcome) {
        return outcome.value().orElseThrow(() ->
                new ChangeRefusedException(outcome.denial().orElseThrow()));
    }

    private <T> CompletableFuture<ServiceResult<T>> transaction(final Work<T> work) {
        return store.<ServiceResult<T>>inTransaction(transaction ->
                        ServiceResult.success(work.perform(transaction)))
                .exceptionally(failure -> {
                    final Throwable cause =
                            failure instanceof CompletionException ? failure.getCause() : failure;
                    if (cause instanceof ChangeRefusedException refused) {
                        return ServiceResult.refused(refused.denial());
                    }
                    throw failure instanceof CompletionException completion
                            ? completion
                            : new CompletionException(failure);
                });
    }

    @FunctionalInterface
    private interface Work<T> {
        T perform(CivicTransaction transaction);
    }
}
