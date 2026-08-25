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
    private final net.riftbreaker.rifttowny.domain.civic.NationCache nationCache;
    private final net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook diplomacy;
    private final net.riftbreaker.rifttowny.domain.justice.Outlaws outlaws;
    private final Consumer<String> warn;

    /**
     * @param warn told about towns that could not be cached, which is a repair case rather than a
     *        routine one. Never silent: an uncacheable town's land denies everything, and an
     *        operator needs to be told why rather than left to guess
     */
    public CivicCacheService(
            final CivicStore store, final CivicCache cache, final Consumer<String> warn) {
        this(store, cache, net.riftbreaker.rifttowny.domain.civic.NationCache.empty(), warn);
    }

    /**
     * The same, with a nation cache to keep in step.
     *
     * <p>Both caches are filled and refreshed by one service on purpose. A town joining a nation
     * changes a row each cache holds a copy of, and two services would be two places to remember —
     * with the failure showing up as a nation whose member list disagrees with its towns.</p>
     */
    public CivicCacheService(
            final CivicStore store,
            final CivicCache cache,
            final net.riftbreaker.rifttowny.domain.civic.NationCache nationCache,
            final Consumer<String> warn
    ) {
        this(store, cache, nationCache,
                net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook.empty(), warn);
    }

    /**
     * The same, keeping the diplomacy book in step too.
     *
     * <p>Here rather than in {@code DiplomacyService} because the event that matters is a nation
     * <em>disbanding</em>, and this is the one thing already told about that. A book that had to be
     * cleaned up by whoever happened to disband the nation would be cleaned up on some paths and
     * not others.</p>
     */
    public CivicCacheService(
            final CivicStore store,
            final CivicCache cache,
            final net.riftbreaker.rifttowny.domain.civic.NationCache nationCache,
            final net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook diplomacy,
            final Consumer<String> warn
    ) {
        this(store, cache, nationCache, diplomacy,
                net.riftbreaker.rifttowny.domain.justice.Outlaws.empty(), warn);
    }

    /**
     * The same, keeping the outlaw book in step too.
     *
     * <p>Here for the same reason the diplomacy book is: the event that matters is a town
     * <em>disbanding</em>, and this is the one thing already told about that on every path that can
     * cause it. A book cleaned up by whoever happened to disband the town would be cleaned up on
     * some paths and not others — which is precisely the gap that let a dissolved nation stay
     * somebody's ally until the next restart.</p>
     */
    public CivicCacheService(
            final CivicStore store,
            final CivicCache cache,
            final net.riftbreaker.rifttowny.domain.civic.NationCache nationCache,
            final net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook diplomacy,
            final net.riftbreaker.rifttowny.domain.justice.Outlaws outlaws,
            final Consumer<String> warn
    ) {
        this.outlaws = Objects.requireNonNull(outlaws, "outlaws");
        this.store = Objects.requireNonNull(store, "store");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.nationCache = Objects.requireNonNull(nationCache, "nationCache");
        this.diplomacy = Objects.requireNonNull(diplomacy, "diplomacy");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /** The cache, for listeners, commands and {@code /rifttowny status}. */
    public CivicCache cache() {
        return cache;
    }

    /** The nation cache, for the listings and the placeholder surface. */
    public net.riftbreaker.rifttowny.domain.civic.NationCache nations() {
        return nationCache;
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
            // In the same transaction as the towns, so the two cannot be filled from either side of
            // a change and disagree about who belongs to what.
            nationCache.replaceAll(transaction.nations().all());
            return new CivicLoad(loaded.size(), List.copyOf(unreadable), nationCache.size());
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
                outlaws.forget(town);
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

    /**
     * Re-reads one nation after it changed.
     *
     * <p>A dissolved nation is forgotten rather than reported as an error, exactly as a disbanded
     * town is. Its member towns are refreshed by whatever changed them; this only maintains the
     * nation's own copy.</p>
     */
    @Override
    public CompletableFuture<Void> refreshNation(
            final net.riftbreaker.rifttowny.domain.org.NationId nation) {
        if (nation == null) {
            return CompletableFuture.completedFuture(null);
        }
        return store.<Void>inTransaction(transaction -> {
            transaction.nations().find(nation).ifPresentOrElse(nationCache::remember, () -> {
                nationCache.forget(nation);
                // A dissolved nation's declarations cascade in the database, and the book has to
                // be told separately or protection keeps granting the ALLY rung to a nation that
                // no longer exists - until the next restart, which is the worst kind of bug to
                // reproduce.
                diplomacy.forget(nation);
            });
            return null;
        });
    }

    /** Drops a town without reading anything, for a caller that already knows it is gone. */
    public void forget(final TownId town) {
        cache.forget(town);
        // The rows cascade with the town; this is the cache being told, and forgetting it here is
        // what stops a disbanded town's grudges outliving it until the next restart.
        outlaws.forget(town);
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
    public record CivicLoad(int towns, List<String> unreadable, int nations) {

        public CivicLoad {
            unreadable = List.copyOf(Objects.requireNonNull(unreadable, "unreadable"));
        }

        public String describe() {
            final String base = "Loaded " + towns + " town(s) and " + nations + " nation(s) "
                    + "into memory";
            return unreadable.isEmpty()
                    ? base + '.'
                    : base + "; " + unreadable.size() + " town(s) could not be read.";
        }

        void warnAbout(final Consumer<String> warn) {
            for (final String town : unreadable) {
                warn.accept("Town " + town + " has no role book and was not cached. Its land will "
                        + "refuse every action until the book is restored.");
            }
        }
    }
}
