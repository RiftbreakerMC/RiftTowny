package net.riftbreaker.rifttowny.domain.bank;

import net.riftbreaker.rifttowny.domain.bank.LedgerEntry.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which way money moved, and how the history reads.
 *
 * <p>The direction on each reason is a table of facts with no logic to derive it from, which is
 * exactly the kind that goes wrong quietly: flip one constant and the ledger reports a payment as
 * income for as long as anybody cares to look. Nothing computes these, nothing cross-checks them,
 * and a wrong one still balances against itself.
 *
 * <p>Pinned as a whole set rather than one assertion each, so a reason added later must be placed
 * deliberately on one side or the other instead of inheriting a default nobody chose.
 */
class LedgerEntryTest {

    private static final String COINS = "coins";

    /** Money arriving in the account. */
    private static final Set<Reason> CREDITS = EnumSet.of(
            Reason.DEPOSIT, Reason.UNCLAIM_REFUND, Reason.TAX, Reason.TRANSFER_IN, Reason.ADMIN);

    /** Money leaving it. */
    private static final Set<Reason> DEBITS = EnumSet.of(
            Reason.WITHDRAWAL, Reason.CLAIM, Reason.RECLAIM, Reason.UPKEEP, Reason.TRANSFER_OUT,
            Reason.ADMIN_REMOVAL);

    private static LedgerEntry entry(final Reason reason, final String amount) {
        return LedgerEntry.of(UUID.randomUUID(), Money.of(new BigDecimal(amount), COINS),
                Money.of(new BigDecimal("100"), COINS), reason, null, null, Instant.EPOCH);
    }

    @Nested
    @DisplayName("which way the money went")
    class Direction {

        @Test
        @DisplayName("every reason is on exactly one side, and the sides are these")
        void theTableIsWhatItSays() {
            for (final Reason reason : Reason.values()) {
                assertThat(reason.credits())
                        .as("%s", reason)
                        .isEqualTo(CREDITS.contains(reason));
            }
        }

        @Test
        @DisplayName("a reason added later is on a side somebody chose")
        void everyReasonIsAccountedFor() {
            // The half of the check that matters most. Without it a new constant simply is not
            // tested: the loop above would pass by treating it as a debit, whatever it should be.
            assertThat(EnumSet.allOf(Reason.class))
                    .as("add it to CREDITS or DEBITS above, and mean it")
                    .isEqualTo(EnumSet.copyOf(union()));
        }

        private Set<Reason> union() {
            final Set<Reason> all = EnumSet.copyOf(CREDITS);
            all.addAll(DEBITS);
            return all;
        }

        @Test
        @DisplayName("tax credits the account, because it is read from the receiver's side")
        void taxIsIncome() {
            // Worth stating rather than leaving to the table: residents pay tax and towns pay
            // upkeep, so from one account's ledger the same word means opposite things, and the
            // entry is always written for the account it belongs to.
            assertThat(Reason.TAX.credits()).isTrue();
            assertThat(Reason.UPKEEP.credits()).isFalse();
        }

        @Test
        @DisplayName("a transfer has two sides and they disagree")
        void transfersAreSymmetric() {
            assertThat(Reason.TRANSFER_IN.credits()).isNotEqualTo(Reason.TRANSFER_OUT.credits());
            assertThat(Reason.ADMIN.credits()).isNotEqualTo(Reason.ADMIN_REMOVAL.credits());
            assertThat(Reason.DEPOSIT.credits()).isNotEqualTo(Reason.WITHDRAWAL.credits());
        }
    }

    @Nested
    @DisplayName("how a line reads")
    class Rendering {

        @Test
        @DisplayName("the sign comes from the reason, not from the amount")
        void signFollowsTheReason() {
            // Amounts are never negative - Money refuses it - so the sign is the only thing
            // telling a player which way their money went.
            assertThat(entry(Reason.DEPOSIT, "10").describe()).startsWith("+10 coins");
            assertThat(entry(Reason.UPKEEP, "10").describe()).startsWith("-10 coins");
        }

        @Test
        @DisplayName("the reason is named, so two movements of the same size are distinguishable")
        void reasonIsNamed() {
            assertThat(entry(Reason.CLAIM, "5").describe()).isEqualTo("-5 coins (CLAIM)");
        }

        @Test
        @DisplayName("an entry with no actor and no detail still renders")
        void anonymousEntriesRender() {
            // The tax run writes entries with no actor: nobody pressed a button. A renderer that
            // needed one would fail on the most numerous rows in the table.
            assertThat(entry(Reason.TAX, "1").author()).isEmpty();
            assertThat(entry(Reason.TAX, "1").note()).isEmpty();
            assertThat(entry(Reason.TAX, "1").describe()).isEqualTo("+1 coins (TAX)");
        }
    }
}
