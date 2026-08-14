package net.riftbreaker.rifttowny.paper.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.riftbreaker.rifttowny.domain.chat.ActiveChannels;
import net.riftbreaker.rifttowny.domain.chat.ChannelAudience;
import net.riftbreaker.rifttowny.domain.chat.ChatChannel;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;
import java.util.Optional;

/**
 * Sends a player's ordinary chat to their active channel.
 *
 * <p><strong>It narrows the audience rather than cancelling the event.</strong> That choice is
 * worth explaining, because cancelling and re-sending is the obvious implementation and it is
 * wrong: it throws away Paper's signed-message handling, and it fires nothing that other chat
 * plugins are listening for, so their moderation, logging and filters silently stop applying to
 * anything said in a town. Narrowing keeps the message inside the pipeline that every other plugin
 * on the server expects to see it in — it simply reaches fewer people.</p>
 *
 * <p><strong>It runs at {@code HIGHEST}, after RiftChat's {@code HIGH}.</strong> RiftChat applies
 * its formatting, emoji and link passes to the message at {@code HIGH} and then installs a renderer
 * that says {@code Channel.GLOBAL}. Running after it means the text keeps all of that and only the
 * renderer is replaced, with one that names the channel the message is actually going to. Running
 * before it would have RiftChat overwrite our renderer and label town chat as global.</p>
 */
public final class ChannelChatListener implements Listener {

    private final ActiveChannels active;
    private final ChannelAudience audiences;
    private final ChannelRenderer renderer;
    private final MessageService messages;

    public ChannelChatListener(
            final ActiveChannels active,
            final ChannelAudience audiences,
            final ChannelRenderer renderer,
            final MessageService messages
    ) {
        this.active = Objects.requireNonNull(active, "active");
        this.audiences = Objects.requireNonNull(audiences, "audiences");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final Player speaker = event.getPlayer();
        final Optional<ChatChannel> channel = active.of(speaker.getUniqueId());
        if (channel.isEmpty()) {
            return;
        }
        final ResidentId who = ResidentId.of(speaker.getUniqueId());
        final Optional<ChannelAudience.Audience> audience =
                audiences.forSpeaker(who, channel.get());

        if (audience.isEmpty()) {
            // They left the town while the channel was still on. Refused rather than delivered to
            // the whole server, which is the failure that matters: somebody says something to their
            // town and everybody reads it.
            event.setCancelled(true);
            active.clear(speaker.getUniqueId());
            messages.send(speaker::sendMessage, MessageKey.CHAT_CHANNEL_LOST,
                    MessageService.value("channel", channel.get().label()));
            return;
        }

        narrowTo(event, audience.get(), speaker);
        applyRenderer(event, channel.get(), speaker);
    }

    /**
     * Removes every player who is not in the channel.
     *
     * <p>Non-player viewers are kept — in practice the console. Town chat is not secret from the
     * person who owns the machine, and a server with no record of it has no way to answer a
     * moderation appeal. What RiftTowny does not build is an <em>in-game</em> spy: nothing here
     * lets one player read another's town chat, which is the line the brief draws.</p>
     *
     * <p>The speaker is always kept. Somebody who cannot see what they just said assumes it did not
     * send and says it again.</p>
     */
    private static void narrowTo(
            final AsyncChatEvent event,
            final ChannelAudience.Audience audience,
            final Player speaker
    ) {
        event.viewers().removeIf(viewer -> {
            if (!(viewer instanceof Player player)) {
                return false;
            }
            return !player.getUniqueId().equals(speaker.getUniqueId())
                    && !audience.includes(ResidentId.of(player.getUniqueId()));
        });
    }

    private void applyRenderer(
            final AsyncChatEvent event, final ChatChannel channel, final Player speaker) {
        final java.util.UUID senderId = speaker.getUniqueId();
        final String senderName = speaker.getName();
        event.renderer((source, displayName, message, viewer) -> renderer.render(
                channel, senderId, senderName, message,
                viewer instanceof Player player ? player : null));
    }

    /** A channel is a mode, and a mode does not survive a logout. */
    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        active.clear(event.getPlayer().getUniqueId());
    }
}
