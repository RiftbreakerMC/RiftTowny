package net.riftbreaker.rifttowny.domain.org;

/**
 * What a player is allowed to write into a board, a tag, a title or a surname.
 *
 * <p>These are the only strings in RiftTowny that one player writes and another player reads
 * verbatim, so the rules live in one place rather than being re-derived at each of the six
 * commands that accept one.</p>
 *
 * <p><strong>Formatting is stripped, not escaped.</strong> The rendering layer already passes
 * these through MiniMessage as unparsed placeholders, so a MiniMessage tag cannot inject
 * anything. The section sign is a different problem: it is applied by the client below
 * MiniMessage, so {@code §k} in a town board would render as scrambled text on every screen
 * that shows it. Removing it at the boundary means no renderer has to remember to.</p>
 */
public final class CivicText {

    /**
     * How long a board may be.
     *
     * <p>Long enough for a sentence and a rule, short enough that it cannot be used to push
     * everything else out of a player's chat window when they join.</p>
     */
    public static final int MAXIMUM_BOARD = 160;

    /**
     * How long a tag may be.
     *
     * <p>A tag sits in front of every message its members send. Four characters is the point of
     * having one; a long tag is a prefix pretending to be an abbreviation.</p>
     */
    public static final int MAXIMUM_TAG = 4;

    /** How long a resident's title or surname may be. Shown beside their name on every line. */
    public static final int MAXIMUM_AFFIX = 16;

    private CivicText() {
    }

    /**
     * Cleans a player-supplied string, or returns empty for one that is only whitespace.
     *
     * <p>Truncates rather than refusing. A player who pastes a paragraph into a board meant to
     * write a sentence, and telling them the whole thing was rejected loses what they typed.</p>
     */
    public static String clean(final String raw, final int maximum) {
        if (raw == null) {
            return "";
        }
        final StringBuilder cleaned = new StringBuilder(Math.min(raw.length(), maximum));
        for (int index = 0; index < raw.length() && cleaned.length() < maximum; index++) {
            final char character = raw.charAt(index);
            // Section signs and control characters both reach the client below MiniMessage, so
            // neither can be neutralised by the renderer that shows this.
            if (character == '§' || Character.isISOControl(character)) {
                continue;
            }
            cleaned.append(character);
        }
        return cleaned.toString().trim();
    }

    /** Whether a cleaned value would be empty, which is how a board or tag is cleared. */
    public static boolean isBlank(final String raw, final int maximum) {
        return clean(raw, maximum).isEmpty();
    }
}
