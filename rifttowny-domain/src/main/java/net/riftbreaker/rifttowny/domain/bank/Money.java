package net.riftbreaker.rifttowny.domain.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * An amount in one currency.
 *
 * <p>{@link BigDecimal}, not {@code double}. A ledger of floating point loses money — {@code 0.1 +
 * 0.2} is not {@code 0.3} — and the error compounds over a year of daily taxes into a discrepancy
 * nobody can explain and no audit can settle.</p>
 *
 * <p>Fixed at four decimal places on construction, so two amounts that represent the same money are
 * the same value. Without that, {@code 5} and {@code 5.00} would be unequal, and a balance check
 * against a price would depend on how the price happened to be typed.</p>
 *
 * <p>Immutable, and never negative: a negative balance is debt, which is
 * {@code RC-FINANCE}'s subject and needs terms, a creditor and a due date rather than a minus
 * sign.</p>
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    /** Enough for any balance a server produces, and exact for any transaction it makes. */
    public static final int SCALE = 4;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.isBlank()) {
            throw new IllegalArgumentException("a currency must have a name");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(
                    "Money cannot be negative, got " + amount + ' ' + currency);
        }
        // HALF_UP rather than the default: a player who is charged 0.00005 more than they expected
        // will never notice, and one who is charged a rounding error in their favour costs the
        // server money every time.
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(final BigDecimal amount, final String currency) {
        return new Money(amount, currency);
    }

    public static Money of(final long amount, final String currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money zero(final String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /**
     * Parses a player-supplied amount.
     *
     * <p>Empty rather than throwing for anything unusable — a typo in a command is a message, and
     * "1e9" or "-5" are typos as far as a deposit is concerned.</p>
     */
    public static Optional<Money> parse(final String raw, final String currency) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            final BigDecimal parsed = new BigDecimal(raw.trim());
            if (parsed.signum() < 0) {
                return Optional.empty();
            }
            return Optional.of(new Money(parsed, currency));
        } catch (final NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /** Rebuilds a stored amount. */
    public static Money fromStorage(final String raw, final String currency) {
        return new Money(new BigDecimal(raw), currency);
    }

    /** The exact decimal string, which is what is stored. */
    public String toStorage() {
        return amount.toPlainString();
    }

    public Money plus(final Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * Subtracts, refusing to go below zero.
     *
     * <p>Empty rather than a negative balance, so a caller cannot spend money the organisation does
     * not have by forgetting to check first.</p>
     */
    public Optional<Money> minus(final Money other) {
        requireSameCurrency(other);
        final BigDecimal remaining = amount.subtract(other.amount);
        return remaining.signum() < 0
                ? Optional.empty()
                : Optional.of(new Money(remaining, currency));
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isAtLeast(final Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) >= 0;
    }

    /**
     * How it reads to a player.
     *
     * <p>Trailing zeros stripped, because "12.5000" is a database detail and "12.5" is money. Never
     * localised here: an economy provider knows the symbol and the convention, and guessing would
     * put a currency name in front of an amount that already has one.</p>
     */
    public String describe() {
        return amount.stripTrailingZeros().toPlainString() + ' ' + currency;
    }

    @Override
    public int compareTo(final Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(final Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            // Refused rather than converted. An exchange rate is a policy decision with a source and
            // a spread, and inventing one inside an arithmetic operator would hide it completely.
            throw new IllegalArgumentException(
                    "Cannot combine " + currency + " with " + other.currency
                            + "; an exchange rate is a decision, not an implicit conversion");
        }
    }
}
