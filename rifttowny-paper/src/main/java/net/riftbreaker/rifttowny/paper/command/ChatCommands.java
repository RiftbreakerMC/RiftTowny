package net.riftbreaker.rifttowny.paper.command;

import net.kyori.adventure.text.Component;
import net.riftbreaker.rifttowny.domain.chat.ActiveChannels;
import net.riftbreaker.rifttowny.domain.chat.ChannelAudience;
import net.riftbreaker.rifttowny.domain.chat.ChatChannel;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.paper.chat.ChannelRenderer;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import net.riftbreaker.rifttowny.paper.command.tree.CommandNode;
import net.riftbreaker.rifttowny.paper.command.tree.Surface;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code /tc} and {@code /nc}.
 *
 * <p>Each does two things, which is Towny's shape and a good one: with a message it sends that one
 * message, and with nothing after it toggles the channel so the next things you type go there
 * too. A player in a town says most of what they say to their town, and prefixing every line with a
 * command is the friction that makes people stop using the channel at all.</p>
 *
 * <p>There is no {@code /ac}. An ally channel needs allies, and allies need
 * {@code RT-MOD-DIPLOMACY}; a command that accepted a message and reached nobody would be worse
 * than one that does not exist, because a player would believe they had been heard.</p>
 */
public final class ChatCommands {

    private final ActiveChannels active;
    private final ChannelAudience audiences;
    private final ChannelRenderer renderer;
    private final MessageService messages;

    public ChatCommands(
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

    /** The {@code /tc} tree. A single action, since a channel command takes free text. */
    public CommandNode townTree() {
        return CommandNode.group("townchat")
                .aliases("tc")
                .permission("rifttowny.chat.town")
                .usage("tc [message]")
                .describedAs("Speak to your town, or switch to its channel")
                .runs((actor, args) -> speak(actor, args, ChatChannel.TOWN), Surface.CHAT);
    }

    /** The {@code /nc} tree. */
    public CommandNode nationTree() {
        return CommandNode.group("nationchat")
                .aliases("nc")
                .permission("rifttowny.chat.nation")
                .usage("nc [message]")
                .describedAs("Speak to your nation, or switch to its channel")
                .runs((actor, args) -> speak(actor, args, ChatChannel.NATION), Surface.CHAT);
    }

    private void speak(
            final CommandActor actor, final List<String> args, final ChatChannel channel) {
        final Optional<ResidentId> who = actor.resident();
        if (who.isEmpty()) {
            messages.send(actor::send, MessageKey.COMMAND_PLAYER_ONLY);
            return;
        }
        final Optional<ChannelAudience.Audience> audience =
                audiences.forSpeaker(who.get(), channel);
        if (audience.isEmpty()) {
            messages.send(actor::send, MessageKey.CHAT_NO_CHANNEL,
                    MessageService.value("channel", channel.label()));
            return;
        }

        if (args.isEmpty()) {
            toggle(actor, who.get(), channel);
            return;
        }
        send(actor, who.get(), channel, audience.get(), String.join(" ", args));
    }

    private void toggle(
            final CommandActor actor, final ResidentId who, final ChatChannel channel) {
        final Optional<ChatChannel> now = active.toggle(who.value(), channel);
        if (now.isPresent()) {
            messages.send(actor::send, MessageKey.CHAT_CHANNEL_ON,
                    MessageService.value("channel", now.get().label()));
        } else {
            messages.send(actor::send, MessageKey.CHAT_CHANNEL_OFF);
        }
    }

    /**
     * Sends one message, without going through the chat pipeline.
     *
     * <p>Composed and delivered here rather than by faking a chat event, because a synthetic
     * {@code AsyncChatEvent} carries no signature and Paper is entitled to reject or warn about
     * one. The trade is that a message sent this way does not pass through other plugins' chat
     * filters — which is why the <em>toggled</em> channel, the one people actually use for
     * conversation, goes through the real pipeline instead.</p>
     */
    private void send(
            final CommandActor actor,
            final ResidentId who,
            final ChatChannel channel,
            final ChannelAudience.Audience audience,
            final String text
    ) {
        final Player speaker = Bukkit.getPlayer(who.value());
        final String senderName = speaker == null ? actor.name() : speaker.getName();
        // Plain text, never parsed. A player typing MiniMessage into town chat must not be able to
        // colour it, fake a system line, or attach a click event to somebody else's screen.
        final Component body = Component.text(text);

        int heard = 0;
        for (final ResidentId member : audience.members()) {
            final Player viewer = Bukkit.getPlayer(member.value());
            if (viewer == null) {
                continue;
            }
            viewer.sendMessage(renderer.render(channel, who.value(), senderName, body, viewer));
            heard++;
        }
        // The speaker sees their own line even if they are somehow not in their own audience, and
        // is told when nobody else was there - otherwise an unanswered message reads as a bug.
        if (!audience.includes(who) && speaker != null) {
            speaker.sendMessage(renderer.render(channel, who.value(), senderName, body, speaker));
        } else if (heard <= 1) {
            messages.send(actor::send, MessageKey.CHAT_NOBODY_HEARD,
                    MessageService.value("channel", channel.label()));
        }
    }
}
