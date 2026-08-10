package net.riftbreaker.rifttowny.domain.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decides whether a storage backend and a network topology may be combined.
 *
 * <p>This is the rule that stops the worst failure mode RiftTowny can have. Two backend servers
 * pointed at one SQLite file do not fail loudly — they lose writes and appear to roll back state at
 * random, and the symptom shows up days later as "my town lost its claims". So SQLite in a shared
 * topology is a refusal to start, not a warning.</p>
 *
 * <p>Kept free of any I/O so the decision itself is testable.</p>
 */
public final class StorageTopologyRule {

    private StorageTopologyRule() {
    }

    /**
     * Every problem with this combination, worst first.
     *
     * @return an empty list when the combination is safe; otherwise problems, at least one of which
     *         is {@link StartupProblem.Severity#ERROR} if startup must abort
     */
    public static List<StartupProblem> evaluate(
            final StorageSettings storage,
            final NetworkTopology topology
    ) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(topology, "topology");

        final List<StartupProblem> problems = new ArrayList<>();

        if (topology.shared() && !storage.backend().supportsSharedTopology()) {
            problems.add(StartupProblem.error(
                    "storage.backend",
                    storage.backend() + " cannot be shared between backend servers. Two servers "
                            + "writing one SQLite database lose writes silently rather than failing, "
                            + "so RiftTowny will not start in this configuration",
                    "Set storage.backend to mariadb for a network install, or set "
                            + "network.shared to false if this is the only backend server"));
        }

        if (topology.shared() && topology.serverId().isBlank()) {
            problems.add(StartupProblem.error(
                    "network.server-id",
                    "a shared install needs a unique server id; outbox ownership, advisory leases "
                            + "and audit rows are all keyed on it",
                    "Set network.server-id to something stable and unique, such as the Velocity "
                            + "server name"));
        }

        if (topology.shared()
                && NetworkTopology.DEFAULT_SERVER_ID.equalsIgnoreCase(topology.serverId())) {
            problems.add(StartupProblem.error(
                    "network.server-id",
                    "'" + NetworkTopology.DEFAULT_SERVER_ID + "' is the single-server default and "
                            + "will collide if a second backend uses it too",
                    "Give each backend server its own network.server-id"));
        }

        if (storage.backend() == StorageBackend.SQLITE
                && storage.poolSize() < StorageSettings.MINIMUM_SQLITE_POOL_SIZE) {
            problems.add(StartupProblem.warning(
                    "storage.pool-size",
                    "a SQLite pool below " + StorageSettings.MINIMUM_SQLITE_POOL_SIZE
                            + " can deadlock, because a repository may open a second connection "
                            + "while still holding the first",
                    "Raised to " + StorageSettings.MINIMUM_SQLITE_POOL_SIZE + " automatically; set "
                            + "storage.pool-size to at least that to silence this"));
        }

        return List.copyOf(problems);
    }

    /** Whether {@link #evaluate} found anything that must stop startup. */
    public static boolean blocksStartup(final List<StartupProblem> problems) {
        return problems.stream().anyMatch(StartupProblem::fatal);
    }
}
