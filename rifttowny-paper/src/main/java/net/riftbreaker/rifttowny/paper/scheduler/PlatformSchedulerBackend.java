package net.riftbreaker.rifttowny.paper.scheduler;

import net.riftbreaker.rifttowny.api.scheduler.RiftTask;

import java.util.UUID;

/**
 * The narrow platform surface a scheduler implementation has to provide.
 *
 * <p>Everything shaped like policy — validating arguments, converting durations to ticks, deciding
 * that {@code Duration.ZERO} means "next tick" — lives in {@link BackedRiftScheduler} and is
 * therefore tested. What is left here is the handful of calls that genuinely differ between Paper
 * and Folia and can only be exercised on a real server.</p>
 *
 * <p>Conventions shared by every method: {@code delayTicks == 0} means as soon as possible, and
 * {@code periodTicks <= 0} means run once.</p>
 */
public interface PlatformSchedulerBackend {

    /** Whether this backend is the Folia one. */
    boolean folia();

    /** Global-region work. Must not touch world or entity state on Folia. */
    RiftTask runGlobal(Runnable task, long delayTicks, long periodTicks);

    /** Work on the region owning a chunk. The only safe way to touch blocks there on Folia. */
    RiftTask runRegion(
            UUID worldId, int chunkX, int chunkZ, Runnable task, long delayTicks, long periodTicks);

    /**
     * Work on whichever thread owns an entity.
     *
     * @param retired invoked instead when the entity no longer exists when the task is due
     */
    RiftTask runEntity(UUID entityId, Runnable task, Runnable retired, long delayTicks);

    /** Off-thread work. Delays are milliseconds here, not ticks: this never touches the tick loop. */
    RiftTask runAsync(Runnable task, long delayMillis, long periodMillis);

    /** Cancels outstanding work and stops any executor this backend owns. */
    void shutdown();
}
