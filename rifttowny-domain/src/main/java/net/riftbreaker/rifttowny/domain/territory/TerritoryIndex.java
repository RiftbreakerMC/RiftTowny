package net.riftbreaker.rifttowny.domain.territory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every claim on the server, in memory, answerable without touching the database.
 *
 * <p>This exists because of one hard constraint: a block-break listener runs on a server thread and
 * must answer "who owns this chunk" before the event returns. A database round trip there is felt
 * as lag on every block a player breaks, and on Folia it is not even legal from a region thread. So
 * the whole claim set is held in memory and kept current by the service that changes it.</p>
 *
 * <p><strong>Holding all of it is deliberate, not lazy.</strong> A claim is a chunk key, a town id,
 * a kind and a timestamp — on the order of a hundred bytes. A server with fifty thousand claimed
 * chunks spends a few megabytes, which is nothing next to the world data it already holds. The
 * alternative, loading per chunk on demand, has to answer the question <em>before</em> the load
 * completes, and every answer it could give while waiting is wrong: allow and a griefer gets a free
 * block, deny and a resident is told they cannot build in their own town.</p>
 *
 * <p>Thread-safe. Listeners read from region threads while the service writes from database
 * threads, so reads must never see a half-updated map. A {@link ConcurrentHashMap} gives that
 * without locking readers, which matters because reads outnumber writes by an enormous margin.</p>
 *
 * <p>Mutable, unlike the aggregates. It is a cache, not a fact — the facts live in
 * {@code rt_claim}, and this is the copy the server thread is allowed to ask.</p>
 */
public final class TerritoryIndex {

    private final Map<ChunkKey, Claim> claims = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong generation = new AtomicLong();

    /** An empty index, before anything is loaded. */
    public static TerritoryIndex empty() {
        return new TerritoryIndex();
    }

    /**
     * Replaces the whole index, as at startup or after a repair.
     *
     * <p>Built into a new map and swapped in rather than cleared and refilled: clearing first would
     * leave a window in which every chunk reads as wilderness, and a player breaking a block during
     * it would be allowed to.</p>
     */
    public void replaceAll(final Collection<Claim> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        final Map<ChunkKey, Claim> replacement = new ConcurrentHashMap<>(Math.max(16, loaded.size()));
        for (final Claim claim : loaded) {
            replacement.put(claim.chunk(), claim);
        }
        claims.keySet().retainAll(replacement.keySet());
        claims.putAll(replacement);
        generation.incrementAndGet();
    }

    /** Records a newly claimed chunk. */
    public void put(final Claim claim) {
        Objects.requireNonNull(claim, "claim");
        claims.put(claim.chunk(), claim);
        generation.incrementAndGet();
    }

    /** Records a released chunk. */
    public void remove(final ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        claims.remove(chunk);
        generation.incrementAndGet();
    }

    /** Records a whole town's territory going away, as on disband. */
    public int removeAllOf(final TownId town) {
        Objects.requireNonNull(town, "town");
        final int before = claims.size();
        claims.values().removeIf(claim -> claim.town().equals(town));
        generation.incrementAndGet();
        return before - claims.size();
    }

    /**
     * The claim on this chunk, if any.
     *
     * <p>The hot path. Everything else here exists to keep this a single map lookup.</p>
     */
    public Optional<Claim> at(final ChunkKey chunk) {
        if (chunk == null) {
            return Optional.empty();
        }
        final Claim claim = claims.get(chunk);
        if (claim == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(claim);
    }

    public Optional<TownId> ownerOf(final ChunkKey chunk) {
        return at(chunk).map(Claim::town);
    }

    /** Whether nobody owns this chunk. */
    public boolean isWilderness(final ChunkKey chunk) {
        return chunk == null || !claims.containsKey(chunk);
    }

    /** Whether this town owns this chunk. */
    public boolean isOwnedBy(final ChunkKey chunk, final TownId town) {
        final Claim claim = chunk == null ? null : claims.get(chunk);
        return claim != null && claim.town().equals(town);
    }

    public int size() {
        return claims.size();
    }

    /**
     * How many chunks a town holds, counted from memory.
     *
     * <p>Linear in the index rather than a map lookup, so it belongs in a dashboard and not in a
     * listener. Named to make that obvious at the call site.</p>
     */
    public int countForTownScanning(final TownId town) {
        Objects.requireNonNull(town, "town");
        return (int) claims.values().stream().filter(claim -> claim.town().equals(town)).count();
    }

    /**
     * A number that changes on every mutation.
     *
     * <p>Lets a downstream cache — a map renderer, a placeholder snapshot — notice that territory
     * moved without diffing the whole index or being told individually.</p>
     */
    public long generation() {
        return generation.get();
    }

    /** Lookup counters for {@code /rifttowny status}, so cache behaviour is observable. */
    public Statistics statistics() {
        return new Statistics(claims.size(), hits.get(), misses.get(), generation.get());
    }

    /**
     * @param misses not a failure: most lookups are wilderness, and a miss is the correct answer
     *        there. A miss rate near zero on a busy server would suggest players never leave claims
     */
    public record Statistics(int claims, long hits, long misses, long generation) {

        public long lookups() {
            return hits + misses;
        }

        public String describe() {
            return claims + " claim(s), " + lookups() + " lookup(s), " + hits + " inside a claim";
        }
    }
}
