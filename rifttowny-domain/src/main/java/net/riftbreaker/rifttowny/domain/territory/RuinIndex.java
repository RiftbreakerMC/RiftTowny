package net.riftbreaker.rifttowny.domain.territory;

import net.riftbreaker.rifttowny.api.ChunkKey;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every standing ruin's chunks, in memory.
 *
 * <p>The third territory question a protection listener asks, after "does a town own this" and
 * before "is this wilderness". It has to be answerable in the same breath as the other two, so it
 * lives here for the same reason {@link TerritoryIndex} does: a block-break listener cannot wait for
 * a database, and on Folia it may not even ask one.</p>
 *
 * <p>Only standing ruins are held. A lapsed or reclaimed ruin owns no chunks — the land is
 * wilderness or somebody's town — and keeping it here would protect ground nothing owns.</p>
 *
 * <p>Thread-safe and mutable, like its companions. The facts live in {@code rt_ruin_claim}; this is
 * the copy the server thread is allowed to ask.</p>
 */
public final class RuinIndex {

    private final Map<ChunkKey, UUID> chunks = new ConcurrentHashMap<>();
    private final Map<UUID, Ruin> ruins = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public static RuinIndex empty() {
        return new RuinIndex();
    }

    /**
     * Replaces everything, as at startup.
     *
     * <p>Built and swapped rather than cleared and refilled: a cleared index reports every ruin as
     * wilderness, and a player breaking a block during that window would be allowed to.</p>
     */
    public synchronized void replaceAll(final Map<Ruin, Set<ChunkKey>> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        final Map<UUID, Ruin> replacementRuins = new HashMap<>();
        final Map<ChunkKey, UUID> replacementChunks = new HashMap<>();
        loaded.forEach((ruin, held) -> {
            replacementRuins.put(ruin.id(), ruin);
            held.forEach(chunk -> replacementChunks.put(chunk, ruin.id()));
        });

        ruins.keySet().retainAll(replacementRuins.keySet());
        ruins.putAll(replacementRuins);
        chunks.keySet().retainAll(replacementChunks.keySet());
        chunks.putAll(replacementChunks);
        generation.incrementAndGet();
    }

    /** Records a newly fallen town's ruin and the chunks it holds. */
    public synchronized void put(final Ruin ruin, final Collection<ChunkKey> held) {
        Objects.requireNonNull(ruin, "ruin");
        Objects.requireNonNull(held, "held");
        ruins.put(ruin.id(), ruin);
        for (final ChunkKey chunk : held) {
            chunks.put(chunk, ruin.id());
        }
        generation.incrementAndGet();
    }

    /** Records a ruin letting go of its land, whether it lapsed or was taken on. */
    public synchronized int remove(final UUID ruinId) {
        if (ruinId == null) {
            return 0;
        }
        ruins.remove(ruinId);
        final int before = chunks.size();
        chunks.values().removeIf(ruinId::equals);
        generation.incrementAndGet();
        return before - chunks.size();
    }

    /** The ruin standing on this chunk, if any. The hot path: one map lookup, then one more. */
    public Optional<Ruin> at(final ChunkKey chunk) {
        if (chunk == null) {
            return Optional.empty();
        }
        final UUID ruinId = chunks.get(chunk);
        return ruinId == null ? Optional.empty() : Optional.ofNullable(ruins.get(ruinId));
    }

    public boolean isRuin(final ChunkKey chunk) {
        return chunk != null && chunks.containsKey(chunk);
    }

    /** Every chunk one ruin still holds. */
    public Set<ChunkKey> chunksOf(final UUID ruinId) {
        if (ruinId == null) {
            return Set.of();
        }
        final Set<ChunkKey> held = new java.util.LinkedHashSet<>();
        chunks.forEach((chunk, owner) -> {
            if (owner.equals(ruinId)) {
                held.add(chunk);
            }
        });
        return Set.copyOf(held);
    }

    public Optional<Ruin> find(final UUID ruinId) {
        return ruinId == null ? Optional.empty() : Optional.ofNullable(ruins.get(ruinId));
    }

    public int size() {
        return ruins.size();
    }

    public int claimedChunks() {
        return chunks.size();
    }

    public long generation() {
        return generation.get();
    }

    public String describe() {
        return size() + " ruin(s) holding " + claimedChunks() + " chunk(s)";
    }
}
