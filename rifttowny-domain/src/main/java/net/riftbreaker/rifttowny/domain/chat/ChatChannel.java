package net.riftbreaker.rifttowny.domain.chat;

import java.util.Locale;
import java.util.Optional;

/**
 * A channel a player can speak into.
 *
 * <p>Two, and deliberately not three. An ally channel needs allies, and allies need
 * {@code RT-MOD-DIPLOMACY}, which is unbuilt — a {@code /ac} that silently reached nobody would be
 * worse than one that does not exist, because a player would use it believing they had been
 * heard.</p>
 */
public enum ChatChannel {

    TOWN("town", "tc"),
    NATION("nation", "nc");

    private final String label;
    private final String command;

    ChatChannel(final String label, final String command) {
        this.label = label;
        this.command = command;
    }

    /** What the channel is called in a message to a player. */
    public String label() {
        return label;
    }

    /** The command that reaches it, for a usage line. */
    public String command() {
        return command;
    }

    public static Optional<ChatChannel> parse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "town", "t", "tc" -> Optional.of(TOWN);
            case "nation", "n", "nc" -> Optional.of(NATION);
            default -> Optional.empty();
        };
    }
}
