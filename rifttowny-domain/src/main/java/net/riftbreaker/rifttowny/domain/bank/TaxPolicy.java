package net.riftbreaker.rifttowny.domain.bank;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * When tax is collected, from whom, and what happens when somebody cannot pay.
 *
 * <p>Everything is zero or off by default, and that is not neutrality: a tax run is the one piece of
 * scheduled work in RiftTowny that can take a player's town away from them, and it should never
 * start happening because somebody upgraded.</p>
 *
 * @param interval how often a run is due. Also the size of the period key that stops a run
 *        happening twice
 * @param residentTax charged to each resident's wallet and paid to their town
 * @param upkeepPerChunk charged to the town treasury for every chunk it holds, and it leaves the
 *        economy. This is the pressure that stops a town claiming land it has no use for
 * @param nationTaxPerTown charged to each member town's treasury and paid to its nation
 * @param grace how long a town may fail to pay before it falls. Zero means the first missed payment
 *        is fatal, which is a defensible setting and a brutal one
 */
public record TaxPolicy(
        boolean enabled,
        Duration interval,
        BigDecimal residentTax,
        BigDecimal upkeepPerChunk,
        BigDecimal nationTaxPerTown,
        Duration grace
) {

    /** The shortest period worth having. Anything under this is a scheduler bug, not a policy. */
    public static final Duration MINIMUM_INTERVAL = Duration.ofMinutes(10);

    public TaxPolicy {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(grace, "grace");
        residentTax = requireNonNegative(residentTax, "residentTax");
        upkeepPerChunk = requireNonNegative(upkeepPerChunk, "upkeepPerChunk");
        nationTaxPerTown = requireNonNegative(nationTaxPerTown, "nationTaxPerTown");
        if (interval.compareTo(MINIMUM_INTERVAL) < 0) {
            interval = MINIMUM_INTERVAL;
        }
    }

    /** Off, which is what a server that has configured nothing gets. */
    public static TaxPolicy off() {
        return new TaxPolicy(
                false, Duration.ofDays(1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                Duration.ofDays(3));
    }

    /** Whether a run would actually do anything. */
    public boolean collectsAnything() {
        return enabled && (charged(residentTax) || charged(upkeepPerChunk)
                || charged(nationTaxPerTown));
    }

    /**
     * The key identifying the period a moment falls in.
     *
     * <p>The instant floored to the interval, so every server on a shared database computes the same
     * key for the same period and exactly one of them wins the insert. That is the whole idempotency
     * guard: not "has it run recently", which is a comparison and therefore a race.</p>
     */
    public String periodKey(final Instant now) {
        Objects.requireNonNull(now, "now");
        final long millis = Math.max(1L, interval.toMillis());
        return Long.toString(Math.floorDiv(now.toEpochMilli(), millis));
    }

    public Money residentTax(final String currency) {
        return Money.of(residentTax, currency);
    }

    /** Upkeep for a town holding this many chunks. */
    public Money upkeepFor(final int chunks, final String currency) {
        return Money.of(upkeepPerChunk.multiply(BigDecimal.valueOf(Math.max(0, chunks))), currency);
    }

    public Money nationTax(final String currency) {
        return Money.of(nationTaxPerTown, currency);
    }

    /** Whether a town that has been unable to pay since this moment has run out of time. */
    public boolean hasRunOutOfTime(final Instant unpaidSince, final Instant now) {
        return unpaidSince != null && !now.isBefore(unpaidSince.plus(grace));
    }

    public static boolean charged(final BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    /** How taxes read in the startup log. */
    public String describe() {
        if (!enabled) {
            return "taxes off";
        }
        return "taxes every " + interval.toHours() + "h: resident " + residentTax
                + ", upkeep " + upkeepPerChunk + "/chunk, nation " + nationTaxPerTown
                + "/town, grace " + grace.toHours() + "h";
    }

    private static BigDecimal requireNonNegative(final BigDecimal value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            // A negative tax is a subsidy, which is a mechanic somebody should have to ask for
            // rather than reach by mistyping a minus sign into a config file.
            throw new IllegalArgumentException("A tax cannot be negative: " + name + " was " + value);
        }
        return value;
    }
}
