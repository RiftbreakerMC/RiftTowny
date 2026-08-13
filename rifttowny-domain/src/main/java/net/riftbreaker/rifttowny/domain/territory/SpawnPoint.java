package net.riftbreaker.rifttowny.domain.territory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.api.WorldPosition;

import java.util.Objects;
import java.util.UUID;

/**
 * A place to arrive, and a direction to face on arriving.
 *
 * <p>Distinct from {@link WorldPosition}, which is a block. A spawn is where a player stands, so its
 * coordinates are fractional — rounding them would drop arrivals into the corner of a block, and on
 * a slab or a stair, inside one. The facing is part of it for the same reason: a spawn that drops
 * everybody looking at a wall is a coordinate, not a spawn.</p>
 *
 * <p>Plain numbers rather than a Bukkit {@code Location}, so it can be read from a database thread,
 * cached, and handed between Folia regions without touching a {@code World}.</p>
 */
public record SpawnPoint(UUID worldId, double x, double y, double z, float yaw, float pitch) {

    public SpawnPoint {
        Objects.requireNonNull(worldId, "worldId");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            // A non-finite coordinate is not a place. Refused here rather than stored, because the
            // failure it causes is a teleport into nothing, well away from whatever wrote it.
            throw new IllegalArgumentException(
                    "A spawn point must be a finite position, got " + x + ", " + y + ", " + z);
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException(
                    "A spawn point must have a finite facing, got " + yaw + ", " + pitch);
        }
    }

    /** The chunk this spawn sits in, which is what territory rules are asked about. */
    public ChunkKey chunk() {
        // Floor rather than cast: (int) -0.5 is 0, which is the wrong side of the origin, and a
        // spawn just west of x=0 would be checked against the chunk next door.
        return new ChunkKey(worldId, Math.floorDiv((int) Math.floor(x), 16),
                Math.floorDiv((int) Math.floor(z), 16));
    }

    /** The block this spawn stands in. */
    public WorldPosition block() {
        return new WorldPosition(
                worldId, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public String describe() {
        return String.format("%.1f, %.1f, %.1f", x, y, z);
    }
}
