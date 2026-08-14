package net.riftbreaker.rifttowny.integrations.chat;

import net.riftbreaker.chat.api.RiftChatService;
import net.riftbreaker.chat.api.RiftChatServices;
import net.riftbreaker.rifttowny.api.capability.Capability;
import net.riftbreaker.rifttowny.integrations.IntegrationAdapter;
import org.bukkit.Server;

import java.util.Objects;
import java.util.Optional;

/**
 * Binds RiftChat, so town and nation chat are rendered by the plugin that owns rendering.
 *
 * <p>RiftTowny decides who hears a channel; RiftChat decides what the line looks like. That split
 * is RiftChat's own, not an assumption — its request type is documented as being supplied "after
 * it has selected the channel and recipients", with rendering the only thing it does.</p>
 *
 * <p>Resolved from the {@code ServicesManager} on every call rather than captured once, because
 * RiftChat replaces its service object when it reloads itself. A reference taken at enable would
 * quietly become a dead one, and the symptom would be town chat rendering with a stale
 * configuration nobody could explain.</p>
 */
public final class RiftChatAdapter implements IntegrationAdapter {

    private final Server server;

    public RiftChatAdapter(final Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public Capability capability() {
        return Capability.CHAT_RIFTCHAT;
    }

    /**
     * Resolves the service, proving the binding rather than merely storing a reference.
     *
     * @throws IllegalStateException when RiftChat is installed but has published no service, which
     *         is a real state: it registers on enable, and a RiftChat that failed to start is
     *         present-but-unusable rather than absent
     */
    @Override
    public Object bind() {
        return RiftChatServices.chat(server).orElseThrow(() -> new IllegalStateException(
                "RiftChat is enabled but has registered no RiftChatService."));
    }

    /** The live service, or empty. Called per message, so it stays a map lookup. */
    public Optional<Object> service() {
        try {
            return RiftChatServices.chat(server).map(found -> found);
        } catch (final RuntimeException | LinkageError unavailable) {
            // RiftChat absent, or present at a version whose api package has moved. Either way the
            // channel falls back to RiftTowny's own plain rendering rather than failing to deliver.
            return Optional.empty();
        }
    }

    @Override
    public String describe(final Object bound) {
        return bound instanceof RiftChatService
                ? "town and nation channels rendered by RiftChat"
                : "bound";
    }
}
