package net.riftbreaker.rifttowny.api.scheduler;

import java.time.Duration;

/**
 * Duration to tick conversion, in one place because both platforms get it wrong in different ways.
 *
 * <p>Folia rejects a delay or period of zero outright, and Paper treats zero as "next tick" — so a
 * caller asking for {@code Duration.ZERO} would behave differently on the two platforms. Everything
 * here clamps to at least one tick, which is what both platforms mean by "as soon as possible".</p>
 */
public final class SchedulerTicks {

    /** Milliseconds in one server tick at the nominal 20 TPS. */
    public static final long MILLIS_PER_TICK = 50L;

    private SchedulerTicks() {
    }

    /**
     * Converts a delay or period to whole ticks, never returning less than one.
     *
     * @throws IllegalArgumentException if the duration is null or negative
     */
    public static long toTicks(final Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("duration must not be null");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative: " + duration);
        }
        final long millis = duration.toMillis();
        // Rounds up, so a sub-tick duration still schedules rather than collapsing to zero.
        final long ticks = (millis + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK;
        return Math.max(1L, ticks);
    }

    /** Converts a duration to whole milliseconds, never returning less than one. */
    public static long toMillis(final Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("duration must not be null");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative: " + duration);
        }
        return Math.max(1L, duration.toMillis());
    }
}
