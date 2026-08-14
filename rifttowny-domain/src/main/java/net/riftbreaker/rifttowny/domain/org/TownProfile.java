package net.riftbreaker.rifttowny.domain.org;

import java.util.Objects;
import java.util.Optional;

/**
 * What a town says about itself, and who it lets in.
 *
 * <p>One value rather than six fields on {@link Town}, because none of these changes an
 * invariant the town enforces. Threading six more parameters through every {@code new Town(...)}
 * would have made the aggregate's real rules — who leads, who belongs, who is trusted — harder
 * to see among the presentation.</p>
 *
 * <p>Two of the six are <strong>not</strong> presentation and are marked as such below. A flag
 * that appears in a placeholder and changes nothing is exactly what the brief forbids, so
 * {@code open} and {@code publicSpawn} each govern something real. {@code neutral} is the
 * honest exception: it is a declared stance that RiftWars will read, and nothing in RiftTowny
 * enforces it today. It is recorded and shown, and that is all — the same standing as a plot
 * marked {@code SHOP}.</p>
 *
 * @param board a message for the town's own members, shown when they arrive
 * @param tag a short abbreviation, shown in front of a member's messages
 * @param colour how the town is drawn on a map, or null for the default
 * @param neutral a declared refusal to take part in war. **Recorded, not enforced**
 * @param open whether anybody may join without being invited. **Enforced**: it is what makes
 *        {@code /town join} succeed for somebody the town never invited
 * @param publicSpawn whether outsiders may travel to the town's spawn. **Enforced**: it is what
 *        makes {@code /town spawn <town>} succeed for somebody who does not live there
 */
public record TownProfile(
        String board,
        String tag,
        MapColour colour,
        boolean neutral,
        boolean open,
        boolean publicSpawn
) {

    private static final TownProfile EMPTY = new TownProfile("", "", null, false, false, false);

    public TownProfile {
        board = CivicText.clean(board, CivicText.MAXIMUM_BOARD);
        tag = CivicText.clean(tag, CivicText.MAXIMUM_TAG);
    }

    /** What a freshly founded town has: nothing said, nobody let in automatically. */
    public static TownProfile empty() {
        return EMPTY;
    }

    public Optional<MapColour> mapColour() {
        return Optional.ofNullable(colour);
    }

    /** The colour to actually draw with, which is the default when none was chosen. */
    public MapColour colourOrDefault() {
        return colour == null ? MapColour.DEFAULT : colour;
    }

    public boolean hasBoard() {
        return !board.isEmpty();
    }

    public boolean hasTag() {
        return !tag.isEmpty();
    }

    public TownProfile withBoard(final String value) {
        return new TownProfile(value, tag, colour, neutral, open, publicSpawn);
    }

    public TownProfile withTag(final String value) {
        return new TownProfile(board, value, colour, neutral, open, publicSpawn);
    }

    public TownProfile withColour(final MapColour value) {
        return new TownProfile(board, tag, value, neutral, open, publicSpawn);
    }

    public TownProfile withNeutral(final boolean value) {
        return new TownProfile(board, tag, colour, value, open, publicSpawn);
    }

    public TownProfile withOpen(final boolean value) {
        return new TownProfile(board, tag, colour, neutral, value, publicSpawn);
    }

    public TownProfile withPublicSpawn(final boolean value) {
        return new TownProfile(board, tag, colour, neutral, open, value);
    }

    /** Rebuilds from storage, where the colour is six hex digits or null. */
    public static TownProfile restore(
            final String board,
            final String tag,
            final String colourHex,
            final boolean neutral,
            final boolean open,
            final boolean publicSpawn
    ) {
        return new TownProfile(
                board, tag,
                // A stored colour that will not parse is treated as no colour rather than as a
                // corrupt row: a town is still a town, and refusing to load it over its map
                // colour would be a catastrophic response to a cosmetic problem.
                colourHex == null ? null : MapColour.parse(colourHex).orElse(null),
                neutral, open, publicSpawn);
    }

    /** The colour as storage holds it, or null. */
    public String colourForStorage() {
        return colour == null ? null : colour.hex();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof TownProfile profile
                && board.equals(profile.board)
                && tag.equals(profile.tag)
                && Objects.equals(colour, profile.colour)
                && neutral == profile.neutral
                && open == profile.open
                && publicSpawn == profile.publicSpawn;
    }

    @Override
    public int hashCode() {
        return Objects.hash(board, tag, colour, neutral, open, publicSpawn);
    }
}
