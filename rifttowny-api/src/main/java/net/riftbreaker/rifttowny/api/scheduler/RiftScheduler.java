package net.riftbreaker.rifttowny.api.scheduler;

import net.riftbreaker.rifttowny.api.WorldPosition;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The one way RiftTowny schedules work.
 *
 * <p>Paper and Folia have incompatible threading models: on Paper there is a main thread and
 * touching world state from anywhere else is a race, while on Folia the world is split into regions
 * that tick independently and touching a region you do not own is a hard failure. No RiftTowny code
 * calls a Bukkit scheduler directly, so that difference is decided once at bootstrap instead of at
 * every call site.</p>
 *
 * <p>Choosing a method is choosing a correctness guarantee:</p>
 * <ul>
 *   <li>{@code global*} — server-wide state that belongs to no region. On Folia this is the global
 *       region tick, which may <em>not</em> touch world or entity state.</li>
 *   <li>{@code region*} — anything that reads or writes blocks, chunks or world state at a
 *       position. This is the only safe way to touch a block on Folia.</li>
 *   <li>{@code entity*} — anything that touches a specific entity, which may move between regions
 *       between scheduling and execution.</li>
 *   <li>{@code async*} — database and network work. Never touches Bukkit state at all.</li>
 * </ul>
 */
public interface RiftScheduler {

    /** Runs on the global region as soon as possible. Must not touch world or entity state. */
    RiftTask global(Runnable task);

    /** Runs on the global region after a delay. */
    RiftTask globalDelayed(Runnable task, Duration delay);

    /** Runs on the global region repeatedly until cancelled. */
    RiftTask globalRepeating(Runnable task, Duration initialDelay, Duration period);

    /** Runs on the region owning {@code position}. The only safe way to touch blocks there. */
    RiftTask region(WorldPosition position, Runnable task);

    /** Runs on the region owning {@code position} after a delay. */
    RiftTask regionDelayed(WorldPosition position, Runnable task, Duration delay);

    /** Runs on the region owning {@code position} repeatedly until cancelled. */
    RiftTask regionRepeating(WorldPosition position, Runnable task, Duration initialDelay, Duration period);

    /**
     * Runs on the thread currently owning the entity.
     *
     * @param retired run instead if the entity no longer exists when the task is due — a player who
     *                logged out, or an entity that was removed. Never silently dropped, because
     *                "the player left" is usually a state the caller needs to unwind.
     */
    RiftTask entity(UUID entityId, Runnable task, Runnable retired);

    /** Runs off any server thread. For database and network work only. */
    RiftTask async(Runnable task);

    /** Runs off any server thread after a delay. */
    RiftTask asyncDelayed(Runnable task, Duration delay);

    /** Runs off any server thread repeatedly until cancelled. */
    RiftTask asyncRepeating(Runnable task, Duration initialDelay, Duration period);

    /**
     * Reads world state at a position and completes with the result.
     *
     * <p>The standard way to answer "what block is there" from a service that is not on a server
     * thread. The supplier runs on the owning region; the future completes wherever the caller
     * chains it.</p>
     *
     * <p>If the supplier throws, the future completes exceptionally — the exception is never
     * swallowed into a null result.</p>
     */
    <T> CompletableFuture<T> supplyRegion(WorldPosition position, Supplier<T> supplier);

    /** Whether the running server is Folia. Reported by {@code /rifttowny status}. */
    boolean isFolia();

    /**
     * Cancels outstanding RiftTowny tasks and stops the async executor.
     *
     * <p>Called on plugin disable. Implementations must not block the server thread indefinitely:
     * in-flight async work that cannot finish promptly is abandoned, and anything that must not be
     * lost belongs in the outbox rather than in a task.</p>
     */
    void shutdown();
}
