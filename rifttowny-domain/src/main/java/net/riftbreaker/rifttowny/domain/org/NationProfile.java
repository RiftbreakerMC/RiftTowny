package net.riftbreaker.rifttowny.domain.org;

import java.util.Objects;
import java.util.Optional;

/**
 * What a nation says about itself.
 *
 * <p>A separate record from {@link TownProfile} rather than a shared one with two unused fields.
 * A nation cannot be {@code open} — towns join nations by invitation on both sides, and there is
 * no such thing as walking in — and it has no spawn to make public. Carrying those two as
 * permanently-false fields would be an invitation to write a command that sets one.</p>
 *
 * @param board a message for the nation's member towns
 * @param tag a short abbreviation
 * @param colour how the nation is drawn on a map, or null for the default
 * @param neutral a declared refusal to take part in war. **Recorded, not enforced** — RiftWars
 *        will read it; nothing in RiftTowny does
 */
public record NationProfile(String board, String tag, MapColour colour, boolean neutral) {

    private static final NationProfile EMPTY = new NationProfile("", "", null, false);

    public NationProfile {
        board = CivicText.clean(board, CivicText.MAXIMUM_BOARD);
        tag = CivicText.clean(tag, CivicText.MAXIMUM_TAG);
    }

    public static NationProfile empty() {
        return EMPTY;
    }

    public Optional<MapColour> mapColour() {
        return Optional.ofNullable(colour);
    }

    public MapColour colourOrDefault() {
        return colour == null ? MapColour.DEFAULT : colour;
    }

    public boolean hasBoard() {
        return !board.isEmpty();
    }

    public boolean hasTag() {
        return !tag.isEmpty();
    }

    public NationProfile withBoard(final String value) {
        return new NationProfile(value, tag, colour, neutral);
    }

    public NationProfile withTag(final String value) {
        return new NationProfile(board, value, colour, neutral);
    }

    public NationProfile withColour(final MapColour value) {
        return new NationProfile(board, tag, value, neutral);
    }

    public NationProfile withNeutral(final boolean value) {
        return new NationProfile(board, tag, colour, value);
    }

    /** Rebuilds from storage. A colour that will not parse is treated as no colour. */
    public static NationProfile restore(
            final String board, final String tag, final String colourHex, final boolean neutral) {
        return new NationProfile(
                board, tag,
                colourHex == null ? null : MapColour.parse(colourHex).orElse(null),
                neutral);
    }

    public String colourForStorage() {
        return colour == null ? null : colour.hex();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof NationProfile profile
                && board.equals(profile.board)
                && tag.equals(profile.tag)
                && Objects.equals(colour, profile.colour)
                && neutral == profile.neutral;
    }

    @Override
    public int hashCode() {
        return Objects.hash(board, tag, colour, neutral);
    }
}
