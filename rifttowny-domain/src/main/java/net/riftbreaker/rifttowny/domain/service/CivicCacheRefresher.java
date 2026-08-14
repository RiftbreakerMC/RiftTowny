package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.concurrent.CompletableFuture;

/**
 * Told when a town changed, so the in-memory civic view can follow.
 *
 * <p>An interface rather than a direct dependency on {@link CivicCacheService} because the services
 * that change towns should not have to know how the cache is filled — only that something has to be
 * told. It also keeps them constructible in a test without a cache at all.</p>
 *
 * <p><strong>Why this exists at all.</strong> Protection is answered from memory. A town whose
 * membership, trust list or roles changed and whose cached copy did not would keep protecting the
 * wrong people: a kicked player would still build, and a new resident would not. There is no
 * acceptable staleness window, so every service method that changes a town ends by calling this.</p>
 */
@FunctionalInterface
public interface CivicCacheRefresher {

    /**
     * Re-reads one town and updates the cache.
     *
     * <p>Called after the transaction commits, never inside it: a rolled-back change that had
     * already reached the cache would leave the server enforcing rules the database never took.</p>
     *
     * @return a future that completes when the cache is current. A town that no longer exists is
     *         dropped rather than reported as an error — disbanding is a legitimate reason to fail
     *         to find one
     */
    CompletableFuture<Void> refresh(TownId town);

    /**
     * Re-reads one nation and updates the cache.
     *
     * <p>A default no-op rather than a second abstract method, which keeps this a functional
     * interface and keeps every existing test's one-line lambda working. The staleness it guards
     * against is milder than the town case — nothing enforces a rule from a nation's cached name —
     * so a caller that only cares about protection can correctly ignore it.</p>
     */
    default CompletableFuture<Void> refreshNation(
            final net.riftbreaker.rifttowny.domain.org.NationId nation) {
        return CompletableFuture.completedFuture(null);
    }

    /** Does nothing. For tests, and for any caller that genuinely holds no cache. */
    static CivicCacheRefresher none() {
        return town -> CompletableFuture.completedFuture(null);
    }
}
