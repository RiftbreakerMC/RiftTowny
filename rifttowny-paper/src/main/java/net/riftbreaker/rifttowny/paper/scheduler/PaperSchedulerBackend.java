package net.riftbreaker.rifttowny.paper.scheduler;

import net.riftbreaker.rifttowny.api.scheduler.RiftTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Paper backend: one main thread, so region and global dispatch are the same thing.
 *
 * <p>Async work uses RiftTowny's own executors rather than Bukkit's async scheduler. Bukkit's
 * async tasks are cancelled during plugin disable in a way that can interrupt a database write
 * mid-transaction; owning the executor means shutdown can be ordered deliberately.</p>
 */
public final class PaperSchedulerBackend implements PlatformSchedulerBackend {

    private final Plugin plugin;
    private final ExecutorService asyncExecutor;
    private final ScheduledExecutorService asyncScheduler;

    public PaperSchedulerBackend(final Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.asyncScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "RiftTowny-async-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public boolean folia() {
        return false;
    }

    @Override
    public RiftTask runGlobal(final Runnable task, final long delayTicks, final long periodTicks) {
        if (periodTicks > 0L) {
            return wrap(Bukkit.getScheduler().runTaskTimer(
                    plugin, task, Math.max(0L, delayTicks), periodTicks));
        }
        if (delayTicks > 0L) {
            return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
        }
        return wrap(Bukkit.getScheduler().runTask(plugin, task));
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
        // On Paper every region is the main thread, so the coordinates carry no dispatch meaning.
        return runGlobal(task, delayTicks, periodTicks);
    }

    @Override
    public RiftTask runEntity(
            final UUID entityId, final Runnable task, final Runnable retired, final long delayTicks) {
        return runGlobal(() -> {
            final Entity entity = Bukkit.getEntity(entityId);
            if (entity == null) {
                retired.run();
            } else {
                task.run();
            }
        }, delayTicks, 0L);
    }

    @Override
    public RiftTask runAsync(final Runnable task, final long delayMillis, final long periodMillis) {
        if (periodMillis > 0L) {
            final ScheduledFuture<?> future = asyncScheduler.scheduleAtFixedRate(
                    () -> asyncExecutor.execute(task),
                    Math.max(0L, delayMillis), periodMillis, TimeUnit.MILLISECONDS);
            return wrap(future);
        }
        if (delayMillis > 0L) {
            final ScheduledFuture<?> future = asyncScheduler.schedule(
                    () -> asyncExecutor.execute(task), delayMillis, TimeUnit.MILLISECONDS);
            return wrap(future);
        }
        asyncExecutor.execute(task);
        return RiftTask.COMPLETED;
    }

    @Override
    public void shutdown() {
        Bukkit.getScheduler().cancelTasks(plugin);
        asyncScheduler.shutdownNow();
        // Shut down without waiting indefinitely: anything that must survive a restart lives in the
        // outbox, and holding the server thread hostage on disable is worse than losing a retry.
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            asyncExecutor.shutdownNow();
        }
    }

    private static RiftTask wrap(final BukkitTask task) {
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

    private static RiftTask wrap(final ScheduledFuture<?> future) {
        final AtomicBoolean cancelled = new AtomicBoolean();
        return new RiftTask() {
            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    future.cancel(false);
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get() || future.isCancelled();
            }
        };
    }
}
