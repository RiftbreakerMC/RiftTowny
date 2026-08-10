package net.riftbreaker.rifttowny.paper.message;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Renders every user-facing string through MiniMessage.
 *
 * <p>Templates are resolved once at load and held in an {@link EnumMap}, so rendering a message
 * never reads a file or a configuration tree. That matters because messages are rendered on the
 * server thread, sometimes inside a movement listener.</p>
 *
 * <p>A template that fails to parse falls back to the shipped default and is reported once. An
 * operator's typo in {@code messages.yml} should cost them their customisation, not the message.</p>
 */
public final class MessageService {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<MessageKey, String> templates = new EnumMap<>(MessageKey.class);
    private final Component prefix;

    /**
     * @param section the {@code messages.yml} root, or null to use the shipped defaults only
     * @param problemReporter told about each template that could not be parsed
     */
    public MessageService(final ConfigurationSection section, final Consumer<String> problemReporter) {
        Objects.requireNonNull(problemReporter, "problemReporter");

        for (final MessageKey key : MessageKey.values()) {
            final String configured = section == null ? null : section.getString(key.path());
            final String template = configured == null || configured.isEmpty()
                    ? key.fallback()
                    : configured;
            templates.put(key, validate(key, template, problemReporter));
        }
        this.prefix = miniMessage.deserialize(templates.get(MessageKey.PREFIX));
    }

    /** Renders a message with no prefix. */
    public Component render(final MessageKey key, final TagResolver... resolvers) {
        Objects.requireNonNull(key, "key");
        return miniMessage.deserialize(templates.get(key), resolvers);
    }

    /** Renders a message behind the RiftTowny prefix. */
    public Component prefixed(final MessageKey key, final TagResolver... resolvers) {
        return prefix.append(render(key, resolvers));
    }

    /** Sends a prefixed message. */
    public void send(final Audience audience, final MessageKey key, final TagResolver... resolvers) {
        Objects.requireNonNull(audience, "audience").sendMessage(prefixed(key, resolvers));
    }

    /** Sends a message with no prefix, for the body lines of a multi-line response. */
    public void sendRaw(final Audience audience, final MessageKey key, final TagResolver... resolvers) {
        Objects.requireNonNull(audience, "audience").sendMessage(render(key, resolvers));
    }

    /**
     * Shorthand for a literal placeholder.
     *
     * <p>{@link Placeholder#unparsed} rather than {@code parsed}: a town name or a player-supplied
     * board is untrusted text, and parsing it would let a player inject MiniMessage tags — colours
     * at best, a fake system message at worst.</p>
     */
    public static TagResolver value(final String name, final Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    /** Every key whose configured template was unusable, for diagnostics. */
    public List<MessageKey> keys() {
        return List.copyOf(templates.keySet());
    }

    private String validate(
            final MessageKey key, final String template, final Consumer<String> problemReporter) {
        try {
            miniMessage.deserialize(template);
            return template;
        } catch (final RuntimeException invalid) {
            problemReporter.accept("Message '" + key.path() + "' is not valid MiniMessage ("
                    + invalid.getMessage() + "); using the built-in default.");
            return key.fallback();
        }
    }
}
