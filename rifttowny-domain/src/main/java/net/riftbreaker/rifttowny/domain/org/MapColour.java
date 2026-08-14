package net.riftbreaker.rifttowny.domain.org;

import java.util.Locale;
import java.util.Optional;

/**
 * The colour an organisation is drawn in.
 *
 * <p>One value rather than a bare string, because a colour is written three different ways by the
 * three things that want it: {@code a1b2c3} in storage, {@code #a1b2c3} for a web map, and
 * {@code <#a1b2c3>} for MiniMessage. Keeping the conversions here means a caller cannot pick the
 * wrong one, and a stored value cannot be a string that only looks like a colour.</p>
 *
 * <p>Parsing is deliberately forgiving about how it is typed and strict about what it accepts. A
 * player types {@code #ff0000}, {@code ff0000} or {@code FF0000} and means the same thing; they
 * never mean {@code red} or {@code ff00}, and guessing at a malformed value would put an
 * arbitrary colour on a map with nothing to say it was arbitrary.</p>
 */
public record MapColour(int rgb) {

    /** The colour used when a town has chosen none. Grey: visible on a map, and clearly a default. */
    public static final MapColour DEFAULT = new MapColour(0x9E9E9E);

    public MapColour {
        if (rgb < 0 || rgb > 0xFFFFFF) {
            throw new IllegalArgumentException(
                    "A map colour is 24 bits, not " + Integer.toHexString(rgb));
        }
    }

    /**
     * Reads a colour a player typed, or empty if it is not one.
     *
     * <p>Empty rather than a fallback: a mistyped colour should be reported, and silently
     * substituting grey would leave the player believing their colour was accepted.</p>
     */
    public static Optional<MapColour> parse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("#")) {
            text = text.substring(1);
        } else if (text.startsWith("0x")) {
            text = text.substring(2);
        }
        if (text.length() != 6) {
            return Optional.empty();
        }
        try {
            return Optional.of(new MapColour(Integer.parseInt(text, 16)));
        } catch (final NumberFormatException notHex) {
            return Optional.empty();
        }
    }

    /** Six lower-case hex digits with no prefix, which is how it is stored. */
    public String hex() {
        return String.format(Locale.ROOT, "%06x", rgb);
    }

    /** {@code #a1b2c3}, which is what a web map and the Towny placeholder surface expect. */
    public String hashHex() {
        return "#" + hex();
    }

    /** {@code <#a1b2c3>}, ready to be prepended to text in a MiniMessage template. */
    public String miniMessage() {
        return "<#" + hex() + '>';
    }

    @Override
    public String toString() {
        return hashHex();
    }
}
