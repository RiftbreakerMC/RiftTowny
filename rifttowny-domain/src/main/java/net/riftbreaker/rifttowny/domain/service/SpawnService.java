package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;
import net.riftbreaker.rifttowny.domain.territory.SpawnPoint;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Where a town's residents arrive.
 *
 * <p>Two rules, and both are about the spawn staying somewhere the town actually controls:</p>
 *
 * <ul>
 *   <li><strong>A spawn must be inside the town's own territory.</strong> Otherwise a mayor sets it
 *       in somebody else's town — or in the middle of a rival's vault — and hands every resident a
 *       free teleport there.</li>
 *   <li><strong>It stops existing when the land does.</strong> Unclaiming the chunk a spawn sits in
 *       clears it, rather than leaving a teleport into what is now wilderness or another town.</li>
 * </ul>
 *
 * <p>The spawn is cached in memory because {@code /town spawn} is a command, not a hot path — but
 * the <em>protection</em> check that happens on arrival is, and the cache is what lets the paper
 * layer resolve a destination without a query while the player is already moving.</p>
 */
public final class SpawnService {

    private final CivicStore store;
    private final Clock clock;
    private final TerritoryIndex territory;
    private final Map<TownId, SpawnPoint> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public SpawnService(
            final CivicStore store, final Clock clock, final TerritoryIndex territory) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.territory = Objects.requireNonNull(territory, "territory");
    }

    /** Fills the cache from storage. Called once at enable. */
    public CompletableFuture<Integer> loadAll() {
        return store.inTransaction(transaction -> {
            final Map<TownId, SpawnPoint> loaded = transaction.spawns().all();
            cache.keySet().retainAll(loaded.keySet());
            cache.putAll(loaded);
            return loaded.size();
        });
    }

    /** A town's spawn, answered from memory. */
    public Optional<SpawnPoint> of(final TownId town) {
        return town == null ? Optional.empty() : Optional.ofNullable(cache.get(town));
    }

    public int cached() {
        return cache.size();
    }

    /**
     * Where this resident may travel to, if they may.
     *
     * <p>Requires {@link Permission#TOWN_SPAWN}, which a town can take away — a probationary role
     * that cannot teleport in is a thing towns want. The chunk is re-checked here rather than
     * trusted: a spawn whose land the town has since released is a teleport into somebody else's
     * territory, and the check happens in the same transaction as the read so it cannot be stale.</p>
     */
    public CompletableFuture<ServiceResult<SpawnPoint>> travelTo(
            final ResidentId actor, final TownId townId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");

        return transaction(transaction -> {
            requirePermission(transaction, townId, actor, Permission.TOWN_SPAWN);
            final SpawnPoint spawn = transaction.spawns().of(townId)
                    .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.NO_TOWN_SPAWN));

            final var owner = transaction.claims().at(spawn.chunk());
            if (owner.isEmpty() || !owner.get().town().equals(townId)) {
                // The land went and the spawn did not. Refused as "no spawn", because that is what
                // it now is; the tidy-up happens below rather than here, since this throw rolls the
                // transaction back and would take a clear with it.
                throw new ChangeRefusedException(ChangeDenial.NO_TOWN_SPAWN);
            }
            return spawn;
        }).thenCompose(result -> {
            if (result.denial().filter(ChangeDenial.NO_TOWN_SPAWN::equals).isEmpty()) {
                return CompletableFuture.completedFuture(result);
            }
            // Its own transaction, so it survives the refusal. Idempotent, and a no-op when the
            // town simply never had a spawn.
            return clearIfOutsideTerritory(townId).thenApply(ignored -> result);
        });
    }

    /**
     * Sets a town's spawn to a position inside its territory.
     *
     * <p>Requires {@link Permission#SET_SPAWN}. The chunk is checked against the claim table inside
     * the transaction rather than against the in-memory index, because a spawn outlives the command
     * that set it and the authoritative answer is the one that will still be true tomorrow.</p>
     */
    public CompletableFuture<ServiceResult<SpawnPoint>> set(
            final ResidentId actor, final TownId townId, final SpawnPoint spawn) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(spawn, "spawn");

        return transaction(transaction -> {
            requirePermission(transaction, townId, actor, Permission.SET_SPAWN);

            final ChunkKey chunk = spawn.chunk();
            final var owner = transaction.claims().at(chunk);
            if (owner.isEmpty()) {
                throw new ChangeRefusedException(ChangeDenial.CHUNK_NOT_CLAIMED);
            }
            if (!owner.get().town().equals(townId)) {
                throw new ChangeRefusedException(ChangeDenial.CHUNK_OWNED_BY_ANOTHER_TOWN);
            }

            transaction.spawns().set(townId, spawn, actor, clock.instant());
            return spawn;
        }).thenApply(result -> {
            result.value().ifPresent(set -> cache.put(townId, set));
            return result;
        });
    }

    /** Removes a town's spawn. Requires {@link Permission#SET_SPAWN}. */
    public CompletableFuture<ServiceResult<TownId>> clear(
            final ResidentId actor, final TownId townId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");

        return transaction(transaction -> {
            requirePermission(transaction, townId, actor, Permission.SET_SPAWN);
            if (!transaction.spawns().clear(townId)) {
                throw new ChangeRefusedException(ChangeDenial.NO_TOWN_SPAWN);
            }
            return townId;
        }).thenApply(result -> {
            result.value().ifPresent(cache::remove);
            return result;
        });
    }

    /**
     * Drops a spawn whose chunk the town no longer owns.
     *
     * <p>Called after territory changes. Returns whether there was one to drop, so a caller can say
     * so — a resident who unclaims the chunk their spawn was in should be told the spawn went with
     * it, rather than discovering it the next time somebody uses the command.</p>
     */
    public CompletableFuture<Boolean> clearIfOutsideTerritory(final TownId townId) {
        Objects.requireNonNull(townId, "townId");
        final Optional<SpawnPoint> spawn = of(townId);
        if (spawn.isEmpty() || territory.isOwnedBy(spawn.get().chunk(), townId)) {
            return CompletableFuture.completedFuture(false);
        }
        return store.inTransaction(transaction -> transaction.spawns().clear(townId))
                .thenApply(removed -> {
                    if (removed) {
                        cache.remove(townId);
                    }
                    return removed;
                });
    }

    /** Forgets a disbanded town's spawn. The row goes with the town; this is the in-memory half. */
    public void forget(final TownId townId) {
        if (townId != null) {
            cache.remove(townId);
        }
    }

    private static void requirePermission(
            final CivicTransaction transaction,
            final TownId townId,
            final ResidentId actor,
            final Permission permission
    ) {
        final Town town = transaction.towns().find(townId)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_FOUND));
        final RoleBook book = transaction.roles()
                .find(OrganisationScope.TOWN, townId.value())
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
        if (!book.allows(actor, permission, town.standingOf(actor))) {
            throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
        }
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
