package net.riftbreaker.rifttowny.paper.scheduler;

import net.riftbreaker.rifttowny.api.WorldPosition;
import net.riftbreaker.rifttowny.api.scheduler.RiftScheduler;
import net.riftbreaker.rifttowny.api.scheduler.RiftTask;
import net.riftbreaker.rifttowny.api.scheduler.SchedulerTicks;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * {@link RiftScheduler} implemented once, over a {@link PlatformSchedulerBackend}.
 *
 * <p>Paper and Folia differ in how work is dispatched, not in what the arguments mean. Keeping the
 * translation here means the rules that are easy to get subtly wrong — a zero duration, a negative
 * period, a region derived from the wrong coordinate shift — are written once and covered by tests,
 * instead of being duplicated in two implementations where only one of them would get fixed.</p>
 */
public final class BackedRiftScheduler implements RiftScheduler {

    /** Immediate dispatch. Distinct from one tick: the backend may run it inline. */
    private static final long IMMEDIATE = 0L;

    /** Run once. Any value at or below zero means no repeat. */
    private static final long NO_REPEAT = 0L;

    private final PlatformSchedulerBackend backend;

    public BackedRiftScheduler(final PlatformSchedulerBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public RiftTask global(final Runnable task) {
        return backend.runGlobal(required(task), IMMEDIATE, NO_REPEAT);
    }

    @Override
    public RiftTask globalDelayed(final Runnable task, final Duration delay) {
        return backend.runGlobal(required(task), SchedulerTicks.toTicks(delay), NO_REPEAT);
    }

    @Override
    public RiftTask globalRepeating(
            final Runnable task, final Duration initialDelay, final Duration period) {
        return backend.runGlobal(
                required(task), SchedulerTicks.toTicks(initialDelay), SchedulerTicks.toTicks(period));
    }

    @Override
    public RiftTask region(final WorldPosition position, final Runnable task) {
        return runRegion(position, required(task), IMMEDIATE, NO_REPEAT);
    }

    @Override
    public RiftTask regionDelayed(
            final WorldPosition position, final Runnable task, final Duration delay) {
        return runRegion(position, required(task), SchedulerTicks.toTicks(delay), NO_REPEAT);
    }

    @Override
    public RiftTask regionRepeating(
            final WorldPosition position,
            final Runnable task,
            final Duration initialDelay,
            final Duration period
    ) {
        return runRegion(position, required(task),
                SchedulerTicks.toTicks(initialDelay), SchedulerTicks.toTicks(period));
    }

    @Override
    public RiftTask entity(final UUID entityId, final Runnable task, final Runnable retired) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(retired, "retired");
        return backend.runEntity(entityId, required(task), retired, IMMEDIATE);
    }

    @Override
    public RiftTask entityDelayed(
            final UUID entityId,
            final Runnable task,
            final Runnable retired,
            final Duration delay
    ) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(retired, "retired");
        return backend.runEntity(entityId, required(task), retired, SchedulerTicks.toTicks(delay));
    }

    @Override
    public RiftTask async(final Runnable task) {
        return backend.runAsync(required(task), IMMEDIATE, NO_REPEAT);
    }

    @Override
    public RiftTask asyncDelayed(final Runnable task, final Duration delay) {
        return backend.runAsync(required(task), SchedulerTicks.toMillis(delay), NO_REPEAT);
    }

    @Override
    public RiftTask asyncRepeating(
            final Runnable task, final Duration initialDelay, final Duration period) {
        return backend.runAsync(
                required(task), SchedulerTicks.toMillis(initialDelay), SchedulerTicks.toMillis(period));
    }

    @Override
    public <T> CompletableFuture<T> supplyRegion(
            final WorldPosition position, final Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        final CompletableFuture<T> future = new CompletableFuture<>();
        region(position, () -> {
            try {
                future.complete(supplier.get());
            } catch (final RuntimeException failure) {
                // Completed exceptionally rather than swallowed. A world read that threw must not
                // arrive at the caller as a null result that then fails somewhere unrelated.
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    @Override
    public boolean isFolia() {
        return backend.folia();
    }

    @Override
    public void shutdown() {
        backend.shutdown();
    }

    private RiftTask runRegion(
            final WorldPosition position,
            final Runnable task,
            final long delayTicks,
            final long periodTicks
    ) {
        Objects.requireNonNull(position, "position");
        // Derived here rather than in each backend so the >> 4 shift exists in one place. Getting
        // it wrong for negative coordinates - which /16 would, and >>4 does not - would schedule
        // onto a neighbouring region and fail only near the origin.
        return backend.runRegion(
                position.worldId(),
                position.x() >> 4,
                position.z() >> 4,
                task,
                delayTicks,
                periodTicks);
    }

    private static Runnable required(final Runnable task) {
        return Objects.requireNonNull(task, "task");
    }
}
