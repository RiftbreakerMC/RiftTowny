package net.riftbreaker.rifttowny.paper.scheduler;

import net.riftbreaker.rifttowny.api.scheduler.RiftScheduler;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/** Picks the scheduler backend once, at bootstrap. */
public final class SchedulerFactory {

    /**
     * The class Folia adds and Paper does not.
     *
     * <p>Detected by name rather than by a server-brand string: a fork can call itself anything,
     * but it cannot run regionised scheduling without this class.</p>
     */
    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    private SchedulerFactory() {
    }

    /** Whether the running server is regionised. */
    public static boolean isFolia() {
        try {
            Class.forName(FOLIA_MARKER);
            return true;
        } catch (final ClassNotFoundException notFolia) {
            return false;
        }
    }

    /** The scheduler for this server. Called once; the result is held for the plugin's lifetime. */
    public static RiftScheduler create(final Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        final PlatformSchedulerBackend backend = isFolia()
                ? new FoliaSchedulerBackend(plugin)
                : new PaperSchedulerBackend(plugin);
        return new BackedRiftScheduler(backend);
    }
}
