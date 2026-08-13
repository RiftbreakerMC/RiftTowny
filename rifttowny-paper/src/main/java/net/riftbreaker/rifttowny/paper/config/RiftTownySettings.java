package net.riftbreaker.rifttowny.paper.config;

import net.riftbreaker.rifttowny.domain.config.NetworkTopology;
import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.config.StorageSettings;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * The configuration RiftTowny actually starts with.
 *
 * @param storage where data lives
 * @param topology where this server sits in the network
 * @param townyPermissionAliases whether {@code towny.*} permissions are accepted alongside
 *        {@code rifttowny.*}, for servers migrating an existing permission set
 */
public record RiftTownySettings(
        StorageSettings storage,
        NetworkTopology topology,
        boolean townyPermissionAliases,
        java.time.Duration ruinLifetime,
        java.time.Duration ruinReclaimDelay,
        java.time.Duration ruinSweepInterval
) {

    public RiftTownySettings {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(ruinLifetime, "ruinLifetime");
        Objects.requireNonNull(ruinReclaimDelay, "ruinReclaimDelay");
        Objects.requireNonNull(ruinSweepInterval, "ruinSweepInterval");
    }

    /** Whether a disbanded town leaves a ruin at all. */
    public boolean ruinsEnabled() {
        return !ruinLifetime.isZero() && !ruinLifetime.isNegative();
    }

    /**
     * Reads {@code config.yml}.
     *
     * <p>Builds the JDBC URL here rather than asking the operator for one: a hand-written URL is
     * where the SQLite pragmas and the MariaDB connection properties get silently lost.</p>
     *
     * @param dataFolder the plugin folder, used to resolve a relative SQLite path
     */
    public static RiftTownySettings from(final FileConfiguration config, final Path dataFolder) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(dataFolder, "dataFolder");

        final StorageBackend backend = StorageBackend.parse(config.getString("storage.backend", "sqlite"));
        final int poolSize = config.getInt("storage.pool-size", backend == StorageBackend.SQLITE ? 4 : 10);
        final long timeout = config.getLong("storage.connection-timeout-millis", 5_000L);

        final StorageSettings storage = switch (backend) {
            case SQLITE -> new StorageSettings(
                    backend,
                    "jdbc:sqlite:" + dataFolder
                            .resolve(config.getString("storage.sqlite.file", "rifttowny.db"))
                            .toAbsolutePath(),
                    "", "", poolSize, timeout);
            case MARIADB -> new StorageSettings(
                    backend,
                    "jdbc:mariadb://"
                            + config.getString("storage.mariadb.host", "127.0.0.1") + ':'
                            + config.getInt("storage.mariadb.port", 3306) + '/'
                            + config.getString("storage.mariadb.database", "rifttowny")
                            + "?useUnicode=true&characterEncoding=utf8",
                    config.getString("storage.mariadb.username", ""),
                    config.getString("storage.mariadb.password", ""),
                    poolSize, timeout);
        };

        final NetworkTopology topology = new NetworkTopology(
                config.getString("network.server-id", NetworkTopology.DEFAULT_SERVER_ID),
                config.getBoolean("network.shared", false));

        // Clamped at zero rather than rejected. A negative lifetime is nonsense, and the nearest
        // sensible reading of it is the one the operator can also write on purpose: ruins off.
        final long ruinHours = Math.max(0L, config.getLong("ruins.lifetime-hours", 72L));
        // Clamped to the window rather than refused: a delay longer than the ruin lasts would be a
        // ruin nobody could ever take on, which is a typo rather than an intention.
        final long reclaimHours = Math.min(
                ruinHours, Math.max(0L, config.getLong("ruins.reclaim-after-hours", 24L)));
        // The sweep floor is a minute: a sweep running every few seconds would be a full table scan
        // in a loop to release land nobody is waiting on.
        final long sweepMinutes = Math.max(1L, config.getLong("ruins.sweep-minutes", 15L));

        return new RiftTownySettings(
                storage,
                topology,
                config.getBoolean("permissions.towny-aliases", false),
                java.time.Duration.ofHours(ruinHours),
                java.time.Duration.ofHours(reclaimHours),
                java.time.Duration.ofMinutes(sweepMinutes));
    }

    /** How ruins read in the startup log. */
    public String describeRuins() {
        return ruinsEnabled()
                ? "ruins stand for " + ruinLifetime.toHours() + "h, reclaimable after "
                        + ruinReclaimDelay.toHours() + "h, swept every "
                        + ruinSweepInterval.toMinutes() + "m"
                : "ruins disabled";
    }

    /** How the topology reads in {@code /rifttowny status}. */
    public String describeTopology() {
        return topology.shared()
                ? "shared network as '" + topology.serverId() + '\''
                : "single server (" + topology.serverId().toLowerCase(Locale.ROOT) + ')';
    }
}
