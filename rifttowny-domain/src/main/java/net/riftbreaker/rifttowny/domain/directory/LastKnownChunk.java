package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.api.ChunkKey;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each online player was last seen standing.
 *
 * <p>Exists for one rule, written down in {@code COMPATIBILITY_MATRIX.md} before any of this was
 * built: location placeholders resolve from the player's last known claim, updated by the movement
 * listener, <strong>never by a synchronous chunk lookup during parsing</strong>.</p>
 *
 * <p>The reason is not performance. A placeholder is resolved by whatever plugin wants it — a
 * scoreboard on a timer, a chat formatter on an async thread, a web map on its own executor — and
 * reading a player's position from an arbitrary thread is illegal on Folia and racy on Paper. The
 * movement listener already runs on the only thread allowed to know where a player is, so it
 * records the answer and everything else reads the record.</p>
 *
 * <p>Online players only. A player who has logged out is not standing anywhere, and their entry is
 * dropped rather than left to answer with wherever they were last week.</p>
 */
public final class LastKnownChunk {

    private final Map<UUID, ChunkKey> chunks = new ConcurrentHashMap<>();

    public static LastKnownChunk empty() {
        return new LastKnownChunk();
    }

    /** Records where somebody is, from the thread that owns them. */
    public void record(final UUID player, final ChunkKey chunk) {
        if (player != null && chunk != null) {
            chunks.put(player, chunk);
        }
    }

    /** Forgets somebody who has left. */
    public void forget(final UUID player) {
        if (player != null) {
            chunks.remove(player);
        }
    }

    /**
     * Where they were last seen, or empty.
     *
     * <p>Empty is the honest answer for an offline player, and every caller renders it as the blank
     * value rather than as wilderness — "not standing anywhere" and "standing in the wild" are
     * different facts, and conflating them would put a player's last position on a scoreboard long
     * after they logged off.</p>
     */
    public Optional<ChunkKey> of(final UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(chunks.get(player));
    }

    public int tracked() {
        return chunks.size();
    }

    /** Replaces everything, for a reload. */
    public synchronized void replaceAll(final Map<UUID, ChunkKey> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        chunks.keySet().retainAll(loaded.keySet());
        chunks.putAll(loaded);
    }
}
