package net.riftbreaker.rifttowny.api.map;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.riftbreaker.rifttowny.api.ChunkKey;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable claims for a single Minecraft world, sampled from the territory cache.
 *
 * <p>The revision is the territory index's global mutation counter: it may
 * advance even when a different world changes. Consumers should compare this
 * counter and discard an obsolete async render, never assume revisions are
 * gap-free or globally persistent across restarts.</p>
 *
 * <p>A snapshot is complete for its world at one in-memory cache revision.
 * Producing it is linear in total cached claims and should run from an
 * asynchronous, rate-limited job, not from a block listener or tick callback.</p>
 */
public record TerritoryMapSnapshot(UUID worldId, long revision, List<MapClaim> claims) {
    public TerritoryMapSnapshot {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(claims, "claims");
        if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
        claims = List.copyOf(claims);
        // Public territory is a map of chunk -> ONE owning town. A damaged
        // upstream index must not give map consumers contradictory owners or
        // duplicate the same claim and leave clients guessing which wins.
        // ChunkKey's record equality handles negative/extreme int coordinates
        // without lossy coordinate packing or integer subtraction overflow.
        final Set<ChunkKey> occupied = new HashSet<>();
        for (MapClaim claim : claims) {
            if (!worldId.equals(claim.chunk().worldId())) {
                throw new IllegalArgumentException("claim belongs to a different world");
            }
            if (!occupied.add(claim.chunk())) {
                throw new IllegalArgumentException(
                        "duplicate claim at chunk " + claim.chunk());
            }
        }
    }
}
