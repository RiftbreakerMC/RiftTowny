package net.riftbreaker.rifttowny.paper.scheduler;

import net.riftbreaker.rifttowny.api.WorldPosition;
import net.riftbreaker.rifttowny.api.scheduler.RiftTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the half of the scheduler that can be tested without a server: argument validation,
 * duration-to-tick conversion, region derivation and failure propagation.
 *
 * <p>The Paper and Folia backends themselves are thin by design and can only be exercised on a real
 * server. They are reported as unverified in the implementation plan rather than assumed correct.</p>
 */
class BackedRiftSchedulerTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private RecordingBackend backend;
    private BackedRiftScheduler scheduler;

    @BeforeEach
    void setUp() {
        backend = new RecordingBackend(false);
        scheduler = new BackedRiftScheduler(backend);
    }

    @Test
    @DisplayName("immediate work is dispatched with no delay and no repeat")
    void immediateWorkHasNoDelay() {
        scheduler.global(() -> { });

        assertThat(backend.calls).singleElement().satisfies(call -> {
            assertThat(call.kind).isEqualTo("global");
            assertThat(call.delay).isZero();
            assertThat(call.period).isZero();
        });
    }

    @Test
    @DisplayName("a sub-tick delay still schedules, rather than collapsing to zero")
    void subTickDelayRoundsUpToOneTick() {
        scheduler.globalDelayed(() -> { }, Duration.ofMillis(1));

        // Folia rejects a zero delay outright and Paper reads it as "next tick", so a sub-tick
        // duration must not reach either backend as zero.
        assertThat(backend.calls.getFirst().delay).isEqualTo(1L);
    }

    @Test
    @DisplayName("durations convert to whole ticks at 20 TPS")
    void durationsConvertToTicks() {
        scheduler.globalRepeating(() -> { }, Duration.ofSeconds(1), Duration.ofSeconds(30));

        final Call call = backend.calls.getFirst();
        assertThat(call.delay).isEqualTo(20L);
        assertThat(call.period).isEqualTo(600L);
    }

    @Test
    @DisplayName("async delays are milliseconds, not ticks")
    void asyncDelaysStayInMilliseconds() {
        scheduler.asyncRepeating(() -> { }, Duration.ofSeconds(1), Duration.ofMinutes(1));

        final Call call = backend.calls.getFirst();
        assertThat(call.kind).isEqualTo("async");
        assertThat(call.delay).isEqualTo(1_000L);
        assertThat(call.period).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("a block position maps to its chunk with an arithmetic shift, so negatives are right")
    void negativeCoordinatesMapToTheCorrectChunk() {
        scheduler.region(new WorldPosition(WORLD, -1, 64, -17), () -> { });

        final Call call = backend.calls.getFirst();
        assertThat(call.kind).isEqualTo("region");
        // -1 / 16 == 0, which would be the neighbouring region. -1 >> 4 == -1, which is correct.
        assertThat(call.chunkX).isEqualTo(-1);
        assertThat(call.chunkZ).isEqualTo(-2);
        assertThat(call.worldId).isEqualTo(WORLD);
    }

    @Test
    @DisplayName("a positive position maps to its chunk")
    void positiveCoordinatesMapToTheCorrectChunk() {
        scheduler.region(new WorldPosition(WORLD, 31, 64, 16), () -> { });

        assertThat(backend.calls.getFirst().chunkX).isEqualTo(1);
        assertThat(backend.calls.getFirst().chunkZ).isEqualTo(1);
    }

    @Test
    @DisplayName("supplyRegion completes with the value the region produced")
    void supplyRegionCompletesWithTheValue() {
        backend.runImmediately = true;

        final CompletableFuture<String> future =
                scheduler.supplyRegion(new WorldPosition(WORLD, 0, 64, 0), () -> "stone");

        assertThat(future).isCompletedWithValue("stone");
    }

    @Test
    @DisplayName("a world read that throws fails the future instead of arriving as null")
    void supplyRegionPropagatesFailure() {
        backend.runImmediately = true;

        final CompletableFuture<String> future = scheduler.supplyRegion(
                new WorldPosition(WORLD, 0, 64, 0),
                () -> {
                    throw new IllegalStateException("chunk not loaded");
                });

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::join).hasRootCauseMessage("chunk not loaded");
    }

    @Test
    @DisplayName("a negative duration is rejected rather than clamped, because it is always a bug")
    void negativeDurationsAreRejected() {
        assertThatThrownBy(() -> scheduler.globalDelayed(() -> { }, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a null task is rejected at the call, not when it is due")
    void nullTasksAreRejectedEagerly() {
        assertThatThrownBy(() -> scheduler.global(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scheduler.region(null, () -> { }))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the entity path carries a retired callback for an entity that has gone")
    void entityWorkCarriesARetiredCallback() {
        final UUID entityId = UUID.randomUUID();
        scheduler.entity(entityId, () -> { }, () -> { });

        final Call call = backend.calls.getFirst();
        assertThat(call.kind).isEqualTo("entity");
        assertThat(call.entityId).isEqualTo(entityId);
        assertThat(call.hasRetired).isTrue();
    }

    @Test
    @DisplayName("the platform flag is reported from the backend, not guessed")
    void platformFlagComesFromTheBackend() {
        assertThat(new BackedRiftScheduler(new RecordingBackend(true)).isFolia()).isTrue();
        assertThat(scheduler.isFolia()).isFalse();
    }

    @Test
    @DisplayName("shutdown reaches the backend")
    void shutdownIsDelegated() {
        scheduler.shutdown();

        assertThat(backend.shutdown).isTrue();
    }

    private record Call(
            String kind, UUID worldId, int chunkX, int chunkZ, UUID entityId,
            long delay, long period, boolean hasRetired) {
    }

    /** Records what the shared layer asked for, and optionally runs the task inline. */
    private static final class RecordingBackend implements PlatformSchedulerBackend {

        private final List<Call> calls = new ArrayList<>();
        private final boolean folia;
        private boolean runImmediately;
        private boolean shutdown;

        private RecordingBackend(final boolean folia) {
            this.folia = folia;
        }

        @Override
        public boolean folia() {
            return folia;
        }

        @Override
        public RiftTask runGlobal(final Runnable task, final long delayTicks, final long periodTicks) {
            calls.add(new Call("global", null, 0, 0, null, delayTicks, periodTicks, false));
            return maybeRun(task);
        }

        @Override
        public RiftTask runRegion(
                final UUID worldId, final int chunkX, final int chunkZ,
                final Runnable task, final long delayTicks, final long periodTicks) {
            calls.add(new Call("region", worldId, chunkX, chunkZ, null, delayTicks, periodTicks, false));
            return maybeRun(task);
        }

        @Override
        public RiftTask runEntity(
                final UUID entityId, final Runnable task, final Runnable retired, final long delayTicks) {
            calls.add(new Call("entity", null, 0, 0, entityId, delayTicks, 0L, retired != null));
            return maybeRun(task);
        }

        @Override
        public RiftTask runAsync(final Runnable task, final long delayMillis, final long periodMillis) {
            calls.add(new Call("async", null, 0, 0, null, delayMillis, periodMillis, false));
            return maybeRun(task);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        private RiftTask maybeRun(final Runnable task) {
            if (runImmediately) {
                task.run();
            }
            return RiftTask.COMPLETED;
        }
    }
}
