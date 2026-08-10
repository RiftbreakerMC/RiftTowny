package net.riftbreaker.rifttowny.api;

import java.util.Objects;
import java.util.UUID;

/**
 * A block position, carried as plain values rather than a Bukkit {@code Location}.
 *
 * <p>This exists so territory questions can be asked from any thread. A {@code Location} holds a
 * {@code World} reference, and on Folia touching one off its owning region is a crash; a record of
 * four numbers is safe to pass anywhere and to cache.</p>
 *
 * @param worldId the world's UUID
 * @param x block x
 * @param y block y
 * @param z block z
 */
public record WorldPosition(UUID worldId, int x, int y, int z) {

    public WorldPosition {
        Objects.requireNonNull(worldId, "worldId");
    }

    /** The chunk containing this position. */
    public ChunkKey chunk() {
        return new ChunkKey(worldId, x >> 4, z >> 4);
    }
}
