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

    /**
     * The chunk the actor is standing in, or empty for the console.
     *
     * <p>Captured at dispatch, which is the only safe moment: a command runs on the thread that
     * owns the player — the main thread on Paper, their region thread on Folia — so reading their
     * position here is legal, and reading it later from a database callback would not be.</p>
     */
    Optional<net.riftbreaker.rifttowny.api.ChunkKey> chunk();

    /**
     * Exactly where the actor is standing, facing included, or empty for the console.
     *
     * <p>Captured at dispatch for the same reason as {@link #chunk()}. Fractional, because a spawn
     * is a standing position rather than a block.</p>
     */
    Optional<net.riftbreaker.rifttowny.domain.territory.SpawnPoint> position();

    boolean hasPermission(String permission);

    void send(Component message);

    default boolean isPlayer() {
        return resident().isPresent();
    }
}
