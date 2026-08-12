package net.riftbreaker.rifttowny.paper.protection;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One message per player per interval.
 *
 * <p>Not politeness — a requirement. A player holding left-click on a protected wall is refused
 * every tick, and twenty chat lines a second scrolls away everything else they were reading,
 * including the explanation itself.</p>
 *
 * <p>Separated from the messenger so the timing can be tested without a server: the interesting
 * parts are the interval boundary and the fact that a wrapping {@code nanoTime} must not jam the
 * throttle shut.</p>
 */
public final class MessageThrottle {

    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();
    private final long intervalNanos;
    private final LongSupplier nanoTime;

    /**
     * @param nanoTime monotonic, not wall-clock. A throttle compared against
     *        {@code currentTimeMillis} would jam or fire early whenever the clock stepped
     */
    public MessageThrottle(final long intervalMillis, final LongSupplier nanoTime) {
        this.intervalNanos = Math.max(0L, intervalMillis) * 1_000_000L;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Whether this player should be told now. Records the send when it says yes. */
    public boolean shouldSend(final UUID player) {
        if (player == null) {
            return false;
        }
        final long now = nanoTime.getAsLong();
        final Long previous = lastSent.get(player);
        // Subtraction rather than comparison, so the check still works across nanoTime wrapping.
        if (previous != null && now - previous < intervalNanos) {
            return false;
        }
        lastSent.put(player, now);
        return true;
    }

    /** Forgets a player who left, so the map stays the size of the player list. */
    public void forget(final UUID player) {
        if (player != null) {
            lastSent.remove(player);
        }
    }

    public int tracked() {
        return lastSent.size();
    }
}
