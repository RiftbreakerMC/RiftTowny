package net.riftbreaker.rifttowny.domain.chat;

import java.util.Locale;
import java.util.Optional;

/**
 * A channel a player can speak into.
 *
 * <p>Three. This said two for a long time, on the grounds that an ally channel needs allies and
 * allies need {@code RT-MOD-DIPLOMACY}, which was unbuilt — and a {@code /ac} that silently reached
 * nobody would be worse than one that does not exist, because a player would use it believing they
 * had been heard. Diplomacy shipped; the reasoning stood unchanged beside a module that had
 * arrived, which is why the channel was still missing.</p>
 *
 * <p>The refusal it describes is still enforced, and now by the audience rather than by the absence
 * of the channel: a speaker with no nation, or a nation with no allies, is told so instead of
 * talking to an empty room.</p>
 */
public enum ChatChannel {

    TOWN("town", "tc"),
    NATION("nation", "nc"),
    ALLY("ally", "ac");

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
            case "ally", "a", "ac" -> Optional.of(ALLY);
            default -> Optional.empty();
        };
    }
}
