package net.riftbreaker.rifttowny.api;

import java.util.Objects;
import java.util.UUID;

/**
 * One 16x16 chunk in one world — the unit of territory.
 *
 * @param worldId the world's UUID
 * @param chunkX chunk x, i.e. block x >> 4
 * @param chunkZ chunk z, i.e. block z >> 4
 */
public record ChunkKey(UUID worldId, int chunkX, int chunkZ) {

    public ChunkKey {
        Objects.requireNonNull(worldId, "worldId");
    }

    /** The chunk immediately adjacent in the given direction, used for border and front checks. */
    public ChunkKey offset(final int deltaX, final int deltaZ) {
        return new ChunkKey(worldId, chunkX + deltaX, chunkZ + deltaZ);
    }

    /** Whether the two chunks share an edge. Diagonals are not adjacent: claims connect orthogonally. */
    public boolean isAdjacentTo(final ChunkKey other) {
        if (other == null || !worldId.equals(other.worldId)) {
            return false;
        }
        final int dx = Math.abs(chunkX - other.chunkX);
        final int dz = Math.abs(chunkZ - other.chunkZ);
        return dx + dz == 1;
    }

    @Override
    public String toString() {
        return worldId + ":" + chunkX + ',' + chunkZ;
    }
}
