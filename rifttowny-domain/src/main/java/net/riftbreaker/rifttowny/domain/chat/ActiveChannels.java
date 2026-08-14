package net.riftbreaker.rifttowny.domain.chat;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which channel each player is currently speaking into.
 *
 * <p>Towny's behaviour, and the reason {@code /tc} with no message does something: a player in a
 * town says most of what they say to their town, and prefixing every line with a command is the
 * kind of friction that makes people stop using the channel.</p>
 *
 * <p>In memory and not persisted, deliberately. A channel is a mode you are in for the next few
 * minutes, and one that survived a logout would be a player logging in tomorrow and saying
 * something to their town that they meant for the server. The cost of forgetting is one command;
 * the cost of remembering wrongly is a message in front of the wrong audience.</p>
 *
 * <p>Thread-safe: chat arrives on the network thread and the commands that change this arrive on
 * the server thread.</p>
 */
public final class ActiveChannels {

    private final Map<UUID, ChatChannel> active = new ConcurrentHashMap<>();

    public static ActiveChannels empty() {
        return new ActiveChannels();
    }

    /** The channel this player is speaking into, or empty for ordinary chat. */
    public Optional<ChatChannel> of(final UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(active.get(player));
    }

    /**
     * Turns a channel on, or off if it was already the active one.
     *
     * <p>One command that toggles rather than two that set and clear, because that is what a player
     * expects from typing {@code /tc} twice.</p>
     *
     * @return the channel now active, or empty if they are back to ordinary chat
     */
    public Optional<ChatChannel> toggle(final UUID player, final ChatChannel channel) {
        if (player == null || channel == null) {
            return Optional.empty();
        }
        final ChatChannel previous = active.get(player);
        if (channel.equals(previous)) {
            active.remove(player);
            return Optional.empty();
        }
        active.put(player, channel);
        return Optional.of(channel);
    }

    /** Puts the player back into ordinary chat. */
    public void clear(final UUID player) {
        if (player != null) {
            active.remove(player);
        }
    }

    public int tracked() {
        return active.size();
    }
}
