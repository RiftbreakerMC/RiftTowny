package net.riftbreaker.rifttowny.paper.chat;

import net.kyori.adventure.text.Component;
import net.riftbreaker.rifttowny.domain.chat.ChatChannel;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a channel message into something a player sees.
 *
 * <p>RiftChat renders it when RiftChat is here, because RiftChat owns formatting and a second
 * formatter is on the project's list of things not to build. When it is not here, RiftTowny renders
 * a plain line itself — a town chat that vanished along with an optional dependency would be worse
 * than a plain one, and every other integration in this plugin degrades the same way.</p>
 *
 * <p>The RiftChat call goes through a supplier rather than a stored service. The service is
 * registered with Bukkit's {@code ServicesManager} and can be replaced when RiftChat reloads
 * itself, so holding a reference from enable would leave us rendering through a dead one.</p>
 */
public final class ChannelRenderer {

    private final MessageService messages;
    private final java.util.function.Supplier<Optional<Object>> riftChat;

    public ChannelRenderer(
            final MessageService messages,
            final java.util.function.Supplier<Optional<Object>> riftChat
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.riftChat = Objects.requireNonNull(riftChat, "riftChat");
    }

    /**
     * Renders one line for one viewer.
     *
     * @param sender who spoke. May be offline by the time this renders, which is why it is a name
     *        and an id rather than a {@link Player}
     * @param message what they said, already through Paper's pipeline — so RiftChat's own emoji,
     *        link and formatting passes have run on it before this is called
     */
    public Component render(
            final ChatChannel channel,
            final UUID sender,
            final String senderName,
            final Component message,
            final Player viewer,
            final String prefix
    ) {
        return throughRiftChat(channel, sender, senderName, message)
                .orElseGet(() -> plain(channel, senderName, message, prefix));
    }

    /**
     * RiftChat's rendering, reached reflectively.
     *
     * <p>Reflective rather than a compile-time call because this class lives in the Paper module,
     * which does not depend on RiftChat — the adapter that does is in the integrations module and
     * is constructed inside the capability guard. Passing the bound service through as
     * {@link Object} keeps the optional dependency optional: a server without RiftChat never loads
     * one of its classes, so there is no {@link NoClassDefFoundError} to catch.</p>
     */
    private Optional<Component> throughRiftChat(
            final ChatChannel channel,
            final UUID sender,
            final String senderName,
            final Component message
    ) {
        final Optional<Object> service = riftChat.get();
        if (service.isEmpty()) {
            return Optional.empty();
        }
        try {
            final Class<?> requestType =
                    Class.forName("net.riftbreaker.chat.api.ChatPresentationRequest");
            final Class<?> serviceType = Class.forName("net.riftbreaker.chat.api.RiftChatService");
            final Class<?> channelType =
                    Class.forName("net.riftbreaker.chat.api.RiftChatService$Channel");

            @SuppressWarnings({"unchecked", "rawtypes"})
            final Object riftChannel = Enum.valueOf((Class<Enum>) channelType, channel.name());
            final Object request = requestType
                    .getMethod("of", channelType, UUID.class, String.class, Component.class)
                    .invoke(null, riftChannel, sender, senderName, message);

            return Optional.ofNullable((Component) serviceType
                    .getMethod("render", requestType)
                    .invoke(service.get(), request));
        } catch (final ReflectiveOperationException | RuntimeException unavailable) {
            // A RiftChat whose API has moved costs the channel its formatting and nothing else.
            // Falling through to the plain renderer keeps the message delivered, which is the part
            // that matters; a failure here must not swallow what somebody said.
            return Optional.empty();
        }
    }

    /**
     * The built-in line, used when RiftChat is not installed.
     *
     * <p>{@code prefix} is the chat prefix of the role the speaker is shown as, or empty. It was
     * stored on every role from the first migration and rendered nowhere; the value is read from
     * the civic cache, which carries a town's role book because protection needs it on every
     * block.</p>
     */
    private Component plain(
            final ChatChannel channel,
            final String senderName,
            final Component message,
            final String prefix) {
        return messages.render(
                channel == ChatChannel.TOWN
                        ? MessageKey.CHAT_TOWN_FORMAT
                        : MessageKey.CHAT_NATION_FORMAT,
                MessageService.value("sender", senderName),
                MessageService.value("prefix", prefix == null ? "" : prefix),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(
                        "message", message));
    }
}
