package net.riftbreaker.rifttowny.domain.territory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One chunk, owned by one town.
 *
 * @param id stable identity, so a claim can be referenced by an area or an audit row after the
 *        chunk has changed hands
 * @param chunk which chunk, in which world
 * @param town the owning town
 * @param kind what it is structurally
 * @param claimedAt when it was taken
 */
public record Claim(UUID id, ChunkKey chunk, TownId town, ClaimKind kind, Instant claimedAt) {

    public Claim {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(town, "town");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(claimedAt, "claimedAt");
    }

    public static Claim of(
            final ChunkKey chunk, final TownId town, final ClaimKind kind, final Instant now) {
        return new Claim(UUID.randomUUID(), chunk, town, kind, now);
    }

    /** The same claim under a different kind, keeping its identity. */
    public Claim as(final ClaimKind newKind) {
        return new Claim(id, chunk, town, newKind, claimedAt);
    }

    public boolean anchorsConnectivity() {
        return kind.anchorsConnectivity();
    }
}
