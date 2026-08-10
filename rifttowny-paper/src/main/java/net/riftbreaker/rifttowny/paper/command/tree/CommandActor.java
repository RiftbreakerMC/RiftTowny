package net.riftbreaker.rifttowny.paper.command.tree;

import net.kyori.adventure.text.Component;
import net.riftbreaker.rifttowny.domain.org.ResidentId;

import java.util.Optional;

/**
 * Whoever is running a command.
 *
 * <p>An interface rather than a {@code CommandSender} so routing, completion and permission
 * filtering can be exercised without a server. The Bukkit implementation is a thin adapter; every
 * rule worth testing lives on this side of it.</p>
 */
public interface CommandActor {

    /** The actor's name, for messages and for logs. */
    String name();

    /**
     * The player running this, or empty for the console.
     *
     * <p>Empty is not an error: plenty of administration is console-appropriate. Actions that need
     * a player say so themselves rather than assuming.</p>
     */
    Optional<ResidentId> resident();

    boolean hasPermission(String permission);

    void send(Component message);

    default boolean isPlayer() {
        return resident().isPresent();
    }
}
