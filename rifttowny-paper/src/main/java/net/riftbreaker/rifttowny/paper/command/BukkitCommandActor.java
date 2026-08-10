package net.riftbreaker.rifttowny.paper.command;

import net.kyori.adventure.text.Component;
import net.riftbreaker.rifttowny.api.scheduler.RiftScheduler;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link CommandSender} seen as a {@link CommandActor}.
 *
 * <p>Thin on purpose: everything worth testing lives behind the interface. Its one real
 * responsibility is threading — a reply usually arrives on a database thread when a service future
 * completes, and on Folia writing to a player from there is not allowed. Sending goes through the
 * scheduler so every call site does not have to remember.</p>
 */
public final class BukkitCommandActor implements CommandActor {

    private final CommandSender sender;
    private final RiftScheduler scheduler;

    public BukkitCommandActor(final CommandSender sender, final RiftScheduler scheduler) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public String name() {
        return sender.getName();
    }

    @Override
    public Optional<ResidentId> resident() {
        return sender instanceof Player player
                ? Optional.of(ResidentId.of(player.getUniqueId()))
                : Optional.empty();
    }

    @Override
    public Optional<net.riftbreaker.rifttowny.api.ChunkKey> chunk() {
        if (!(sender instanceof Player player)) {
            return Optional.empty();
        }
        final var location = player.getLocation();
        return Optional.of(new net.riftbreaker.rifttowny.api.ChunkKey(
                location.getWorld().getUID(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4));
    }

    @Override
    public boolean hasPermission(final String permission) {
        return sender.hasPermission(permission);
    }

    @Override
    public void send(final Component message) {
        Objects.requireNonNull(message, "message");
        if (sender instanceof Player player) {
            // On the player's own thread, and with a retired callback that simply drops the
            // message: a reply to somebody who has already logged out is not an error worth logging.
            scheduler.entity(player.getUniqueId(), () -> player.sendMessage(message), () -> { });
            return;
        }
        scheduler.global(() -> sender.sendMessage(message));
    }

    /** The wrapped sender, for the rare case a caller genuinely needs Bukkit. */
    public CommandSender sender() {
        return sender;
    }
}
