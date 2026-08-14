package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The land around somebody, square by square.
 *
 * <p>A player standing at a border cannot see it. Territory is invisible until it refuses you
 * something, and "why can I not build here" is the single most common question a town plugin
 * generates. The map answers it before it is asked, which is why it is worth building properly
 * rather than as an afterthought.</p>
 *
 * <p>Answered entirely from the three in-memory indexes, so it costs no query and can be rendered on
 * the thread the command arrived on — which on Folia is the only thread allowed to look at the
 * player's position at all.</p>
 *
 * <p><strong>Orientation is fixed: north is up.</strong> Rows run from the lowest z at the top to
 * the highest at the bottom, and columns from the lowest x on the left to the highest on the right,
 * which is exactly how the game's own compass and coordinates read. Rotating the map to the player's
 * facing was considered and rejected: a map that turns under you is unreadable while you walk, and
 * every other map a player has ever used points north.</p>
 */
public final class TerritoryMap {

    /**
     * Columns either side of the centre.
     *
     * <p>Fifteen columns at two characters each is thirty, which fits a default chat window without
     * wrapping. Wrapping is not a cosmetic problem here: a wrapped row splits into two lines and the
     * grid stops being a grid.</p>
     */
    public static final int DEFAULT_RADIUS_X = 7;

    /**
     * Rows either side of the centre.
     *
     * <p>Nine rows plus a header and a legend is most of a chat window, which is about as much as a
     * command may take before it is scrolling away everything else that was said.</p>
     */
    public static final int DEFAULT_RADIUS_Z = 4;

    /** Beyond this the map stops being a chat message. */
    public static final int MAXIMUM_RADIUS = 24;

    private final TerritoryIndex claims;
    private final RuinIndex ruins;
    private final CivicCache towns;

    public TerritoryMap(
            final TerritoryIndex claims, final RuinIndex ruins, final CivicCache towns) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.ruins = Objects.requireNonNull(ruins, "ruins");
        this.towns = Objects.requireNonNull(towns, "towns");
    }

    /** The default view around somebody. */
    public MapView around(final ChunkKey centre, final ResidentId viewer) {
        return around(centre, viewer, DEFAULT_RADIUS_X, DEFAULT_RADIUS_Z);
    }

    /**
     * The land around a chunk, as it looks to one person.
     *
     * @param viewer whoever is reading it, or null for the console — everything claimed then reads
     *        as somebody else's, which is the truthful answer for a viewer who owns nothing
     */
    public MapView around(
            final ChunkKey centre, final ResidentId viewer, final int radiusX, final int radiusZ) {
        Objects.requireNonNull(centre, "centre");
        final int spanX = Math.min(MAXIMUM_RADIUS, Math.max(0, radiusX));
        final int spanZ = Math.min(MAXIMUM_RADIUS, Math.max(0, radiusZ));

        final Optional<TownId> homeTown = viewer == null ? Optional.empty() : towns.townOf(viewer);
        final Optional<NationId> homeNation =
                homeTown.flatMap(towns::nationOf);

        final List<List<MapCell>> rows = new ArrayList<>(spanZ * 2 + 1);
        for (int dz = -spanZ; dz <= spanZ; dz++) {
            final List<MapCell> row = new ArrayList<>(spanX * 2 + 1);
            for (int dx = -spanX; dx <= spanX; dx++) {
                row.add(cell(centre.offset(dx, dz), dx == 0 && dz == 0, viewer, homeTown, homeNation));
            }
            rows.add(List.copyOf(row));
        }
        return new MapView(List.copyOf(rows), centre, spanX, spanZ);
    }

    private MapCell cell(
            final ChunkKey chunk,
            final boolean isCentre,
            final ResidentId viewer,
            final Optional<TownId> homeTown,
            final Optional<NationId> homeNation
    ) {
        final Optional<Claim> claim = claims.at(chunk);
        if (claim.isPresent()) {
            final Claim held = claim.get();
            return new MapCell(
                    chunk,
                    standingOf(held, viewer, homeTown, homeNation),
                    held.town(),
                    towns.town(held.town()).map(facts -> facts.displayName()).orElse("a town"),
                    held.kind(),
                    isCentre);
        }
        // Ruins are checked after claims, never before: a reclaimed ruin's chunks belong to the town
        // that took them on, and asking the ruin index first would draw somebody's town as rubble.
        return ruins.at(chunk)
                .map(ruin -> new MapCell(
                        chunk, MapCell.MapStanding.RUIN, null, ruin.name().display(), null, isCentre))
                .orElseGet(() -> MapCell.wilderness(chunk, isCentre));
    }

    private MapCell.MapStanding standingOf(
            final Claim claim,
            final ResidentId viewer,
            final Optional<TownId> homeTown,
            final Optional<NationId> homeNation
    ) {
        // Ordered narrowest first. A plot you hold is inside your own town and your own town is
        // inside your nation, so a wider test placed earlier would swallow the narrower ones and
        // the map would lose exactly the distinctions it exists to draw.
        if (viewer != null && claim.isHeldBy(viewer)) {
            return MapCell.MapStanding.OWN_PLOT;
        }
        if (homeTown.isPresent() && homeTown.get().equals(claim.town())) {
            return MapCell.MapStanding.OWN_TOWN;
        }
        if (homeNation.isPresent()
                && towns.nationOf(claim.town()).filter(homeNation.get()::equals).isPresent()) {
            return MapCell.MapStanding.NATION;
        }
        return MapCell.MapStanding.FOREIGN;
    }

    /**
     * A rendered view, and the answers a header needs.
     *
     * @param rows north at index zero, so {@code rows.get(0).get(0)} is the north-west corner
     */
    public record MapView(List<List<MapCell>> rows, ChunkKey centre, int radiusX, int radiusZ) {

        public MapView {
            Objects.requireNonNull(rows, "rows");
            Objects.requireNonNull(centre, "centre");
            rows = List.copyOf(rows);
        }

        public int width() {
            return radiusX * 2 + 1;
        }

        public int height() {
            return radiusZ * 2 + 1;
        }

        /** The square the viewer is standing in. */
        public MapCell here() {
            return rows.get(radiusZ).get(radiusX);
        }

        /** How many of the squares shown are claimed by somebody, for a one-line summary. */
        public int claimedSquares() {
            int claimed = 0;
            for (final List<MapCell> row : rows) {
                for (final MapCell cell : row) {
                    if (cell.isClaimed()) {
                        claimed++;
                    }
                }
            }
            return claimed;
        }
    }
}
