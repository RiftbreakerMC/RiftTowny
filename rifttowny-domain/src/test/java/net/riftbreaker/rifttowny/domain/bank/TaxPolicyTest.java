package net.riftbreaker.rifttowny.domain.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules a tax run is decided by.
 *
 * <p>Two of them are unusually consequential for their size. {@code periodKey} is the identity of a
 * whole run — it is what several servers sharing a database agree on, and what stops one period
 * being collected twice — and {@code hasRunOutOfTime} is what ends a town. Both were exercised only
 * through the service, where a wrong answer looks like a scheduling quirk rather than a rule.
 */
class TaxPolicyTest {

    private static final String COINS = "coins";
    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    private static TaxPolicy policy(
            final boolean enabled,
            final String resident,
            final String upkeep,
            final String nation,
            final String maxResident) {
        return new TaxPolicy(enabled, Duration.ofDays(1), new BigDecimal(resident),
                new BigDecimal(upkeep), new BigDecimal(nation), Duration.ofDays(3),
                new BigDecimal(maxResident));
    }

    @Nested
    @DisplayName("whether a run happens at all")
    class Running {

        @Test
        @DisplayName("a server that charges nothing and forbids town rates runs nothing")
        void nothingToCollect() {
            assertThat(policy(true, "0", "0", "0", "0").collectsAnything()).isFalse();
        }

        @Test
        @DisplayName("disabled means disabled, whatever the rates say")
        void disabledWins() {
            assertThat(policy(false, "5", "5", "5", "5").collectsAnything()).isFalse();
        }

        @Test
        @DisplayName("any one server rate is enough")
        void anyServerRate() {
            assertThat(policy(true, "1", "0", "0", "0").collectsAnything()).isTrue();
            assertThat(policy(true, "0", "1", "0", "0").collectsAnything()).isTrue();
            assertThat(policy(true, "0", "0", "1", "0").collectsAnything()).isTrue();
        }

        @Test
        @DisplayName("and so is permitting towns to charge, even when the server charges nothing")
        void townRatesAloneAreEnough() {
            // The bug this closes. A server that charges nothing itself and lets each town decide
            // is the natural way to use per-town tax, and it skipped the run entirely: every town
            // rate was stored, shown in the command, and never collected. Nothing would have said
            // so - a run that does not happen looks exactly like a run that found nothing to do.
            assertThat(policy(true, "0", "0", "0", "100").collectsAnything()).isTrue();
        }
    }

    @Nested
    @DisplayName("which period a moment belongs to")
    class Periods {

        private TaxPolicy every(final Duration interval) {
            return new TaxPolicy(true, interval, BigDecimal.ONE, BigDecimal.ZERO,
                    BigDecimal.ZERO, Duration.ofDays(3), BigDecimal.ZERO);
        }

        @Test
        @DisplayName("two moments in one interval agree, which is what stops a double collection")
        void sameIntervalSameKey() {
            final TaxPolicy daily = every(Duration.ofDays(1));

            assertThat(daily.periodKey(NOW)).isEqualTo(daily.periodKey(NOW.plusSeconds(3600)));
        }

        @Test
        @DisplayName("the next interval is a different period, or tax would be collected once ever")
        void nextIntervalDiffers() {
            final TaxPolicy daily = every(Duration.ofDays(1));

            assertThat(daily.periodKey(NOW.plus(Duration.ofDays(1))))
                    .isNotEqualTo(daily.periodKey(NOW));
        }

        @Test
        @DisplayName("the key is absolute, so several servers compute the same one")
        void keyIsAbsolute() {
            // Nothing about it depends on when a server started or where it is: the whole
            // arrangement for a shared database is that every server names the period identically
            // and exactly one wins the insert.
            assertThat(every(Duration.ofHours(6)).periodKey(NOW))
                    .isEqualTo(every(Duration.ofHours(6)).periodKey(NOW));
        }

        @Test
        @DisplayName("moments before the epoch still land in one period each")
        void beforeTheEpoch() {
            // floorDiv rather than division, for the same reason chunk coordinates use a shift: a
            // plain division truncates toward zero and would put two different intervals either
            // side of 1970 into one key.
            final TaxPolicy daily = every(Duration.ofDays(1));
            final Instant before = Instant.parse("1969-12-30T00:00:00Z");

            assertThat(daily.periodKey(before))
                    .isNotEqualTo(daily.periodKey(before.plus(Duration.ofDays(1))));
        }

        @Test
        @DisplayName("a zero interval does not divide by zero")
        void zeroInterval() {
            assertThat(every(Duration.ZERO).periodKey(NOW)).isNotBlank();
        }
    }

    @Nested
    @DisplayName("when a town has run out of time")
    class Grace {

        private final TaxPolicy threeDays = policy(true, "1", "0", "0", "0");

        @Test
        @DisplayName("a town that has never missed a payment is never out of time")
        void neverUnpaid() {
            assertThat(threeDays.hasRunOutOfTime(null, NOW)).isFalse();
        }

        @Test
        @DisplayName("inside the grace period it survives")
        void insideGrace() {
            assertThat(threeDays.hasRunOutOfTime(NOW.minus(Duration.ofDays(2)), NOW)).isFalse();
        }

        @Test
        @DisplayName("the moment grace expires it falls, and not a moment before")
        void graceBoundary() {
            // The boundary is the whole rule: a day early and a town is destroyed for a debt it
            // still had time to pay. Inclusive at the exact instant, because "three days" that
            // needs three days and a millisecond is not three days.
            final Instant unpaid = NOW.minus(Duration.ofDays(3));

            assertThat(threeDays.hasRunOutOfTime(unpaid, NOW)).isTrue();
            assertThat(threeDays.hasRunOutOfTime(unpaid, NOW.minusMillis(1))).isFalse();
        }
    }

    @Nested
    @DisplayName("upkeep")
    class Upkeep {

        @Test
        @DisplayName("scales with the land held")
        void scalesWithChunks() {
            assertThat(policy(true, "0", "7", "0", "0").upkeepFor(3, COINS))
                    .isEqualTo(Money.of(new BigDecimal("21"), COINS));
        }

        @Test
        @DisplayName("a town with no land owes nothing, and a negative count cannot pay it back")
        void noLandNoUpkeep() {
            final TaxPolicy seven = policy(true, "0", "7", "0", "0");

            assertThat(seven.upkeepFor(0, COINS).isZero()).isTrue();
            assertThat(seven.upkeepFor(-5, COINS).isZero()).isTrue();
        }
    }
}
