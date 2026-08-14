package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;

import java.util.Objects;
import java.util.Optional;

/**
 * One chunk on the map, and everything the renderer needs to draw it.
 *
 * <p>What it does <em>not</em> carry is a colour or a glyph. Those are presentation, they belong to
 * whichever surface is drawing — chat today, a Bedrock form or a web map later — and putting them
 * here would mean the domain deciding what shade of green a nation is.</p>
 *
 * @param town the owning town, or null in wilderness and on a ruin
 * @param label what to call this square: the town's name, the fallen town's name on a ruin, or
 *        empty in wilderness. Resolved here because the caller has no other way to turn a
 *        {@link TownId} into a name without a query per square
 * @param kind what the claim is structurally, or null when nothing is claimed here
 * @param standing how this square relates to the person looking at it
 * @param isCentre whether the viewer is standing in this chunk
 */
public record MapCell(
        ChunkKey chunk,
        MapStanding standing,
        TownId town,
        String label,
        ClaimKind kind,
        boolean isCentre
) {

    public MapCell {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(standing, "standing");
        label = label == null ? "" : label;
    }

    /** Unclaimed ground. */
    public static MapCell wilderness(final ChunkKey chunk, final boolean isCentre) {
        return new MapCell(chunk, MapStanding.WILDERNESS, null, "", null, isCentre);
    }

    public Optional<TownId> owner() {
        return Optional.ofNullable(town);
    }

    public Optional<ClaimKind> claimKind() {
        return Optional.ofNullable(kind);
    }

    /** Whether anything is claimed here at all. A ruin counts: it is held, just not by a town. */
    public boolean isClaimed() {
        return standing != MapStanding.WILDERNESS;
    }

    /**
     * How a square relates to whoever is reading the map.
     *
     * <p>Deliberately about the <em>viewer</em> rather than about the land. The same chunk is a
     * different colour to its owner, to their neighbour and to a passing stranger, and a map that
     * told everybody the same thing would be a map nobody could read at a glance.</p>
     */
    public enum MapStanding {

        /** Nobody's. */
        WILDERNESS,

        /** A plot inside the viewer's own town that the viewer personally holds. */
        OWN_PLOT,

        /** The viewer's own town. */
        OWN_TOWN,

        /** Another town in the viewer's nation. */
        NATION,

        /** Somebody else's town. */
        FOREIGN,

        /** A fallen town's land, standing until it lapses or is taken on. */
        RUIN
    }
}
