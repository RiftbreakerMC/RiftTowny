package net.riftbreaker.rifttowny.domain.bank;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What things cost.
 *
 * <p>One value rather than a scattering of configuration reads, so a price cannot be charged from
 * one place and quoted from another. Everything here is per-server configuration; nothing is
 * per-town, because a town setting its own claim price would be setting its own difficulty.</p>
 *
 * <p><strong>Zero means free, and free is the default.</strong> A server that installs RiftTowny
 * and configures nothing should find claiming works, not find that nobody can afford to. Prices are
 * something an operator turns on.</p>
 *
 * @param townFounding charged to the founder's own wallet — the town does not exist yet to pay
 * @param claim charged to the town treasury for each chunk taken
 * @param claimRefund returned to the treasury when a chunk is released. Deliberately separate from
 *        {@code claim} rather than a percentage of it: a refund equal to the price makes claiming
 *        free to experiment with, and one far below it makes a misclick expensive, and which of
 *        those a server wants is not something to infer
 * @param plot charged to a resident's wallet when they take a plot, and paid to the town
 * @param reclaim charged to whoever rebuilds a ruin. The one price with a gameplay purpose beyond
 *        economy: it is what stops a wealthy player collecting every fallen town
 * @param spawnTravel charged to a resident for travelling to their own town spawn
 */
public record CivicPrices(
        BigDecimal townFounding,
        BigDecimal claim,
        BigDecimal claimRefund,
        BigDecimal plot,
        BigDecimal reclaim,
        BigDecimal spawnTravel
) {

    public CivicPrices {
        townFounding = requireNonNegative(townFounding, "townFounding");
        claim = requireNonNegative(claim, "claim");
        claimRefund = requireNonNegative(claimRefund, "claimRefund");
        plot = requireNonNegative(plot, "plot");
        reclaim = requireNonNegative(reclaim, "reclaim");
        spawnTravel = requireNonNegative(spawnTravel, "spawnTravel");
    }

    /** Everything free, which is what a server that has configured nothing gets. */
    public static CivicPrices free() {
        return new CivicPrices(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Whether anything costs anything. */
    public boolean anyCharged() {
        return isCharged(townFounding) || isCharged(claim) || isCharged(plot)
                || isCharged(reclaim) || isCharged(spawnTravel);
    }

    public Money townFounding(final String currency) {
        return Money.of(townFounding, currency);
    }

    public Money claim(final String currency) {
        return Money.of(claim, currency);
    }

    public Money claimRefund(final String currency) {
        return Money.of(claimRefund, currency);
    }

    public Money plot(final String currency) {
        return Money.of(plot, currency);
    }

    public Money reclaim(final String currency) {
        return Money.of(reclaim, currency);
    }

    public Money spawnTravel(final String currency) {
        return Money.of(spawnTravel, currency);
    }

    /** Whether a price is actually charged, as opposed to configured as zero. */
    public static boolean isCharged(final BigDecimal price) {
        return price != null && price.signum() > 0;
    }

    /** How prices read in the startup log. */
    public String describe() {
        return anyCharged()
                ? "found " + townFounding + ", claim " + claim + " (refund " + claimRefund
                        + "), plot " + plot + ", reclaim " + reclaim + ", spawn " + spawnTravel
                : "everything free";
    }

    private static BigDecimal requireNonNegative(final BigDecimal value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            // A negative price is a payment for doing something, which is a mechanic rather than a
            // typo - and not one anybody asked for. Refused at startup rather than discovered when
            // a player claims a thousand chunks for profit.
            throw new IllegalArgumentException(
                    "A price cannot be negative: " + name + " was " + value);
        }
        return value;
    }
}
