package net.riftbreaker.rifttowny.paper.spawn;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * How long a player must wait between spawn travels.
 *
 * <p>Not politeness this time — it is the only thing standing between {@code /town spawn} and an
 * escape hatch from every fight on the server. A player who can teleport home the moment a fight
 * turns has not lost the fight, and a cooldown is the cheapest rule that changes that.</p>
 *
 * <p>Pure, and monotonic: a wall clock that steps backwards would hand out a free teleport, and one
 * that steps forwards would strand somebody.</p>
 */
public final class SpawnCooldown {

    private final Map<UUID, Long> lastTravelled = new ConcurrentHashMap<>();
    private final long cooldownNanos;
    private final LongSupplier nanoTime;

    public SpawnCooldown(final Duration cooldown, final LongSupplier nanoTime) {
        Objects.requireNonNull(cooldown, "cooldown");
        this.cooldownNanos = Math.max(0L, cooldown.toNanos());
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Whether this player has to wait, and for how long. Empty when they may go now. */
    public java.util.Optional<Duration> remaining(final UUID player) {
        if (player == null || cooldownNanos == 0L) {
            return java.util.Optional.empty();
        }
        final Long previous = lastTravelled.get(player);
        if (previous == null) {
            return java.util.Optional.empty();
        }
        // Subtraction rather than comparison, so this still works across a nanoTime wrap.
        final long elapsed = nanoTime.getAsLong() - previous;
        return elapsed >= cooldownNanos
                ? java.util.Optional.empty()
                : java.util.Optional.of(Duration.ofNanos(cooldownNanos - elapsed));
    }

    /**
     * Records a travel.
     *
     * <p>Called on arrival, not on the attempt. A cancelled or refused teleport should not cost a
     * player their next one — otherwise walking into a wall during the warmup is punished harder
     * than arriving.</p>
     */
    public void started(final UUID player) {
        if (player != null && cooldownNanos > 0L) {
            lastTravelled.put(player, nanoTime.getAsLong());
        }
    }

    /** Forgets a player who left. */
    public void forget(final UUID player) {
        if (player != null) {
            lastTravelled.remove(player);
        }
    }

    public int tracked() {
        return lastTravelled.size();
    }

    /** A wait a player can act on: seconds, because a cooldown is short by construction. */
    public static String describe(final Duration remaining) {
        final long seconds = Math.max(1L, (remaining.toMillis() + 999L) / 1000L);
        return seconds + "s";
    }
}
