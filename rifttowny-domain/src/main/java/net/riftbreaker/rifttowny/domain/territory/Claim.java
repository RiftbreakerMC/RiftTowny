package net.riftbreaker.rifttowny.domain.territory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One chunk, owned by one town, and possibly held by one of its residents.
 *
 * <p>The plot lives on the claim rather than in a table beside it, and the reason is the protection
 * check. "Does this player own the plot they are standing on" is the {@link
 * net.riftbreaker.rifttowny.domain.flag.Relationship#RESIDENT} rung, asked on every block a player
 * touches; keeping it here means the answer comes from the same map lookup that already found the
 * chunk's town, rather than from a second index that could disagree with it.</p>
 *
 * @param id stable identity, so a claim can be referenced by an area or an audit row after the
 *        chunk has changed hands
 * @param chunk which chunk, in which world
 * @param town the owning town
 * @param kind what it is structurally
 * @param type what it is for. Independent of {@code kind}: changing a plot's use must never change
 *        whether the town is still contiguous
 * @param owner the resident who holds this plot, or null if the town holds it directly
 * @param claimedAt when the town took it
 */
public record Claim(
        UUID id,
        ChunkKey chunk,
        TownId town,
        ClaimKind kind,
        PlotType type,
        ResidentId owner,
        Instant claimedAt
) {

    public Claim {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(town, "town");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(claimedAt, "claimedAt");
    }

    /** A chunk the town has just taken: an ordinary plot, held by the town itself. */
    public static Claim of(
            final ChunkKey chunk, final TownId town, final ClaimKind kind, final Instant now) {
        return new Claim(UUID.randomUUID(), chunk, town, kind, PlotType.DEFAULT, null, now);
    }

    /** The same claim under a different kind, keeping its identity, plot type and holder. */
    public Claim as(final ClaimKind newKind) {
        return new Claim(id, chunk, town, newKind, type, owner, claimedAt);
    }

    /** The same claim put to a different use. */
    public Claim asType(final PlotType newType) {
        return new Claim(id, chunk, town, kind, Objects.requireNonNull(newType, "newType"),
                owner, claimedAt);
    }

    /** The same claim, held by a resident. */
    public Claim heldBy(final ResidentId resident) {
        return new Claim(id, chunk, town, kind, type,
                Objects.requireNonNull(resident, "resident"), claimedAt);
    }

    /** The same claim, back in the town's hands. */
    public Claim released() {
        return new Claim(id, chunk, town, kind, type, null, claimedAt);
    }

    public Optional<ResidentId> holder() {
        return Optional.ofNullable(owner);
    }

    public boolean isHeld() {
        return owner != null;
    }

    /** Whether this particular resident holds this plot. The RESIDENT rung, in one comparison. */
    public boolean isHeldBy(final ResidentId who) {
        return owner != null && owner.equals(who);
    }

    public boolean anchorsConnectivity() {
        return kind.anchorsConnectivity();
    }
}
