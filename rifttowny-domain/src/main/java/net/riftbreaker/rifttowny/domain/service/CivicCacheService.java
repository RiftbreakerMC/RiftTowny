package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Keeps the in-memory civic view in step with the database.
 *
 * <p>The counterpart to {@link TerritoryService#loadIndex()}: that fills the territory index, this
 * fills the town facts that go with it. Between them a protection listener can answer without a
 * single query.</p>
 *
 * <p>Every read here is a whole town — its residents, its trust list, its role book — because that
 * is the unit {@link TownFacts} replaces. Partial updates were considered and rejected: a cache that
 * could hold a town's new membership beside its old roles has states the database never has.</p>
 */
public final class CivicCacheService implements CivicCacheRefresher {

    private final CivicStore store;
    private final CivicCache cache;
    private final Consumer<String> warn;

    /**
     * @param warn told about towns that could not be cached, which is a repair case rather than a
     *        routine one. Never silent: an uncacheable town's land denies everything, and an
     *        operator needs to be told why rather than left to guess
     */
    public CivicCacheService(
            final CivicStore store, final CivicCache cache, final Consumer<String> warn) {
        this.store = Objects.requireNonNull(store, "store");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /** The cache, for listeners, commands and {@code /rifttowny status}. */
    public CivicCache cache() {
        return cache;
    }

    /**
     * Fills the cache from storage.
     *
     * <p>Called once at enable and waited on, before the server accepts players. A partially loaded
     * cache reports towns as unknown, and an unknown town's land refuses everything — safe, but it
     * would mean a resident could not build in their own town until the load finished.</p>
     */
    public CompletableFuture<CivicLoad> loadAll() {
        return store.inTransaction(transaction -> {
            final List<Town> towns = transaction.towns().all();
            final List<TownFacts> loaded = new ArrayList<>(towns.size());
            final List<String> unreadable = new ArrayList<>();
            for (final Town town : towns) {
                final Optional<RoleBook> roles = rolesOf(transaction, town.id());
                if (roles.isEmpty()) {
                    unreadable.add(town.name().display());
                    continue;
                }
                loaded.add(TownFacts.of(town, roles.get()));
            }
            cache.replaceAll(loaded);
            return new CivicLoad(loaded.size(), List.copyOf(unreadable));
        }).thenApply(summary -> {
            summary.warnAbout(warn);
            return summary;
        });
    }

    /**
     * Re-reads one town after it changed.
     *
     * <p>A fresh read rather than the caller handing over what it just wrote. The caller has a town
     * <em>or</em> a role book, rarely both, and threading whichever half it happens to hold through
     * every service method is how one path ends up forgetting. One extra read on a command that
     * already did several is not worth the risk.</p>
     */
    @Override
    public CompletableFuture<Void> refresh(final TownId town) {
        if (town == null) {
            return CompletableFuture.completedFuture(null);
        }
        return store.<Void>inTransaction(transaction -> {
            final Optional<Town> found = transaction.towns().find(town);
            if (found.isEmpty()) {
                // Disbanded between the change and this read, or disbanded by the change itself.
                cache.forget(town);
                return null;
            }
            final Optional<RoleBook> roles = rolesOf(transaction, town);
            if (roles.isEmpty()) {
                // A town with no role book cannot answer a permission question. Dropping it makes
                // its land deny everything, which is the safe direction, and the warning names it.
                cache.forget(town);
                warn.accept("Town " + found.get().name().display() + " has no role book. Its land "
                        + "will refuse every action until the book is restored.");
                return null;
            }
            cache.remember(TownFacts.of(found.get(), roles.get()));
            return null;
        });
    }

    /** Drops a town without reading anything, for a caller that already knows it is gone. */
    public void forget(final TownId town) {
        cache.forget(town);
    }

    private static Optional<RoleBook> rolesOf(
            final CivicTransaction transaction, final TownId town) {
        return transaction.roles().find(OrganisationScope.TOWN, town.value());
    }

    /**
     * What a load found.
     *
     * @param unreadable towns that had no role book, named so an operator can repair them
     */
    public record CivicLoad(int towns, List<String> unreadable) {

        public CivicLoad {
            unreadable = List.copyOf(Objects.requireNonNull(unreadable, "unreadable"));
        }

        public String describe() {
            return unreadable.isEmpty()
                    ? "Loaded " + towns + " town(s) into memory."
                    : "Loaded " + towns + " town(s) into memory; " + unreadable.size()
                            + " could not be read.";
        }

        void warnAbout(final Consumer<String> warn) {
            for (final String town : unreadable) {
                warn.accept("Town " + town + " has no role book and was not cached. Its land will "
                        + "refuse every action until the book is restored.");
            }
        }
    }
}
