package net.riftbreaker.rifttowny.paper.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.riftbreaker.rifttowny.api.scheduler.RiftTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Folia backend: the world is split into regions that tick independently.
 *
 * <p>The rules this class exists to honour: global-region work may not touch world or entity state,
 * positional work must run on the region owning that chunk, and entity work must run on whichever
 * thread currently owns the entity — which may change between scheduling and execution.</p>
 *
 * <p>Delays are clamped to at least one tick because Folia rejects zero outright, where Paper reads
 * zero as "next tick". {@code SchedulerTicks} already guarantees that for caller-supplied durations;
 * the clamps here cover the internal immediate path.</p>
 */
public final class FoliaSchedulerBackend implements PlatformSchedulerBackend {

    private final Plugin plugin;

    public FoliaSchedulerBackend(final Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean folia() {
        return true;
    }

    @Override
    public RiftTask runGlobal(final Runnable task, final long delayTicks, final long periodTicks) {
        if (periodTicks > 0L) {
            return wrap(Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, ignored -> task.run(), atLeastOneTick(delayTicks), periodTicks));
        }
        if (delayTicks > 0L) {
            return wrap(Bukkit.getGlobalRegionScheduler()
                    .runDelayed(plugin, ignored -> task.run(), delayTicks));
        }
        return wrap(Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run()));
    }

    @Override
    public RiftTask runRegion(
            final UUID worldId,
            final int chunkX,
            final int chunkZ,
            final Runnable task,
            final long delayTicks,
            final long periodTicks
    ) {
        final World world = Bukkit.getWorld(worldId);
        if (world == null) {
            // Falling back to the global region would be worse than doing nothing: this task exists
            // to touch world state, and the global region may not. Report it instead of pretending.
            plugin.getLogger().warning(
                    "Dropped region-scheduled work for unknown or unloaded world " + worldId
                            + ". This is a bug if the world is loaded.");
            return RiftTask.COMPLETED;
        }

        if (periodTicks > 0L) {
            return wrap(Bukkit.getRegionScheduler().runAtFixedRate(
                    plugin, world, chunkX, chunkZ, ignored -> task.run(),
                    atLeastOneTick(delayTicks), periodTicks));
        }
        if (delayTicks > 0L) {
            return wrap(Bukkit.getRegionScheduler().runDelayed(
                    plugin, world, chunkX, chunkZ, ignored -> task.run(), delayTicks));
        }
        return wrap(Bukkit.getRegionScheduler().run(
                plugin, world, chunkX, chunkZ, ignored -> task.run()));
    }

    @Override
    public RiftTask runEntity(
            final UUID entityId, final Runnable task, final Runnable retired, final long delayTicks) {
        final Entity entity = Bukkit.getEntity(entityId);
        if (entity == null) {
            // Already gone before we could schedule. The caller's retired path is the correct
            // response, and running it inline is what the entity scheduler would have done.
            retired.run();
            return RiftTask.COMPLETED;
        }
        if (delayTicks > 0L) {
            return wrap(entity.getScheduler()
                    .runDelayed(plugin, ignored -> task.run(), retired, delayTicks));
        }
        return wrap(entity.getScheduler().run(plugin, ignored -> task.run(), retired));
    }

    @Override
    public RiftTask runAsync(final Runnable task, final long delayMillis, final long periodMillis) {
        if (periodMillis > 0L) {
            return wrap(Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin, ignored -> task.run(),
                    Math.max(1L, delayMillis), periodMillis, TimeUnit.MILLISECONDS));
        }
        if (delayMillis > 0L) {
            return wrap(Bukkit.getAsyncScheduler()
                    .runDelayed(plugin, ignored -> task.run(), delayMillis, TimeUnit.MILLISECONDS));
        }
        return wrap(Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run()));
    }

    @Override
    public void shutdown() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    private static long atLeastOneTick(final long ticks) {
        return Math.max(1L, ticks);
    }

    private static RiftTask wrap(final ScheduledTask task) {
        if (task == null) {
            // Folia returns null when the owning plugin is being disabled. Nothing will run, and
            // nothing needs cancelling.
            return RiftTask.COMPLETED;
        }
        return new RiftTask() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }
}
