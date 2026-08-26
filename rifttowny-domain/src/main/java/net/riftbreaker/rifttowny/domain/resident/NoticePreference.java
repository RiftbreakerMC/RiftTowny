package net.riftbreaker.rifttowny.domain.resident;

import java.util.Locale;
import java.util.Optional;

/**
 * What a player has chosen about territory notices.
 *
 * <p>Three values and no fourth. "Follow the server" is deliberately <em>not</em> a constant here —
 * it is the absence of a choice, expressed as the absence of a row, and giving it a name would let
 * it be stored. A stored {@code DEFAULT} would freeze the operator's setting as it stood the moment
 * the player happened to type the command, so a server that later moved its notices to the action
 * bar would leave behind everybody who had ever run this command without meaning to pin anything.</p>
 *
 * <p>{@link #CHAT} and {@link #ACTION_BAR} are not two features. The listener already ends in
 * {@code if (actionBar) sendActionBar else sendMessage}; these pick which branch that takes for one
 * player, and {@link #OFF} means neither is reached.</p>
 */
public enum NoticePreference {

    /** No territory notices at all. */
    OFF,

    /** In chat, where they scroll away with everything else. */
    CHAT,

    /** Above the hotbar, where they replace each other rather than accumulating. */
    ACTION_BAR;

    /** Whether a notice should be sent at all. */
    public boolean announces() {
        return this != OFF;
    }

    /** Whether it goes above the hotbar rather than into chat. */
    public boolean usesActionBar() {
        return this == ACTION_BAR;
    }

    /**
     * Parses a stored or typed value.
     *
     * <p>Empty rather than throwing, like every other parse here: a typo is a message, and a value
     * written by a later version must not stop the set loading.</p>
     */
    public static Optional<NoticePreference> parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        final String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (final NoticePreference value : values()) {
            if (value.name().equals(normalised)) {
                return Optional.of(value);
            }
        }
        // The word a player types for the action bar is one word; the constant has an underscore.
        return "ACTIONBAR".equals(normalised) ? Optional.of(ACTION_BAR) : Optional.empty();
    }

    /** How it is typed, for a completer and for echoing a choice back. */
    public String typed() {
        return this == ACTION_BAR ? "actionbar" : name().toLowerCase(Locale.ROOT);
    }

    /** The words a player may type, the one that clears a choice included. */
    public static String options() {
        return "off, chat, actionbar, default";
    }
}
