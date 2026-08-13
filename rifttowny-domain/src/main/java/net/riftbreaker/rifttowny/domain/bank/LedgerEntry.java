package net.riftbreaker.rifttowny.domain.bank;

import net.riftbreaker.rifttowny.domain.org.ResidentId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One movement of an organisation's money.
 *
 * <p>Kept forever. A treasury with no history is a number nobody can argue with, and the first
 * question after any large withdrawal is who took it — a question the balance alone can never
 * answer.</p>
 *
 * @param amount always positive; {@link #reason} says which way it went
 * @param balance what the account held afterwards, recorded rather than recomputed. A ledger that
 *        has to be replayed from the beginning to be read is one nobody reads
 * @param actor whoever did it, or null for a movement the system made — a tax run, an upkeep charge
 * @param detail free text for the reason, such as the chunk a claim was paid for
 */
public record LedgerEntry(
        UUID id,
        UUID accountId,
        Money amount,
        Money balance,
        Reason reason,
        ResidentId actor,
        String detail,
        Instant occurredAt
) {

    public LedgerEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static LedgerEntry of(
            final UUID accountId,
            final Money amount,
            final Money balance,
            final Reason reason,
            final ResidentId actor,
            final String detail,
            final Instant now
    ) {
        return new LedgerEntry(
                UUID.randomUUID(), accountId, amount, balance, reason, actor, detail, now);
    }

    public Optional<ResidentId> author() {
        return Optional.ofNullable(actor);
    }

    public Optional<String> note() {
        return Optional.ofNullable(detail);
    }

    /** A line for a history listing. */
    public String describe() {
        return (reason.credits() ? "+" : "-") + amount.describe() + " (" + reason + ')';
    }

    /**
     * Why money moved.
     *
     * <p>The constant name is the stored value, so renaming one is a migration. Every reason says
     * which direction it goes, because "a withdrawal of a negative amount" is how a ledger ends up
     * with entries nobody can total.</p>
     */
    public enum Reason {

        /** A player put their own money in. */
        DEPOSIT(true),
        /** A player took money out. */
        WITHDRAWAL(false),
        /** Paid for territory. */
        CLAIM(false),
        /** Refunded for territory given up. */
        UNCLAIM_REFUND(true),
        /** Paid to take on a ruin. */
        RECLAIM(false),
        /** Collected from residents or member towns. */
        TAX(true),
        /** Paid to the server for existing. */
        UPKEEP(false),
        /** Moved between two civic accounts. */
        TRANSFER_IN(true),
        TRANSFER_OUT(false),
        /** An operator put it there or took it away. */
        ADMIN(true),
        ADMIN_REMOVAL(false);

        private final boolean credits;

        Reason(final boolean credits) {
            this.credits = credits;
        }

        /** Whether this reason adds to the balance. */
        public boolean credits() {
            return credits;
        }

        public String storageValue() {
            return name();
        }

        /** Empty for a reason this version does not know, so one row cannot stop a history loading. */
        public static Optional<Reason> parse(final String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            for (final Reason reason : values()) {
                if (reason.name().equals(raw)) {
                    return Optional.of(reason);
                }
            }
            return Optional.empty();
        }
    }
}
