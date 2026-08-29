package net.riftbreaker.rifttowny.domain.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The value every transaction goes through.
 *
 * <p>Exercised only indirectly until now, through the bank and tax service tests, which means its
 * rules were being relied on rather than checked. They are the kind worth checking: a rounding
 * direction, a refusal to go negative, and a refusal to add two currencies together are each one
 * character away from a bug nobody notices until an audit.
 */
class MoneyTest {

    private static final String COINS = "coins";

    private static Money coins(final String amount) {
        return Money.of(new BigDecimal(amount), COINS);
    }

    @Nested
    @DisplayName("what an amount may be")
    class Invariants {

        @Test
        @DisplayName("never negative, because a balance that can go below zero is a hole")
        void negativeIsRefused() {
            assertThatThrownBy(() -> coins("-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("rounded to four places, half up, which favours the server")
        void roundsHalfUp() {
            // Stated in the class as a deliberate choice: a player charged 0.00005 more than they
            // expected never notices, and one charged a rounding error in their favour costs the
            // server money on every transaction. HALF_EVEN would split the difference and is the
            // usual default, which is exactly why this is worth pinning.
            assertThat(coins("1.00005").toStorage()).isEqualTo("1.0001");
            assertThat(coins("1.00004").toStorage()).isEqualTo("1.0000");
        }

        @Test
        @DisplayName("a blank currency is refused, since an amount of nothing is not money")
        void currencyIsRequired() {
            assertThatThrownBy(() -> Money.of(BigDecimal.ONE, " "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("scale is normalised, so the same amount typed two ways is one amount")
        void scaleIsNormalised() {
            // BigDecimal.equals compares scale, so without normalisation 1 and 1.00 would be
            // different values for the same money and a balance would stop matching itself after a
            // round trip. The same trap TownProfile's tax rate fell into.
            assertThat(coins("1")).isEqualTo(coins("1.00"));
            assertThat(coins("1").hashCode()).isEqualTo(coins("1.0000").hashCode());
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("subtracting more than there is answers empty rather than a negative")
        void minusRefusesToGoNegative() {
            // Empty rather than a negative balance, so a caller cannot spend money an organisation
            // does not have by forgetting to check first.
            assertThat(coins("5").minus(coins("6"))).isEmpty();
            assertThat(coins("5").minus(coins("5"))).contains(coins("0"));
        }

        @Test
        @DisplayName("adding and subtracting round-trip exactly")
        void addAndSubtract() {
            assertThat(coins("10.25").plus(coins("0.75"))).isEqualTo(coins("11"));
            assertThat(coins("11").minus(coins("0.75"))).contains(coins("10.25"));
        }

        @Test
        @DisplayName("two currencies are never combined, because a rate is a decision")
        void currenciesDoNotMix() {
            final Money gems = Money.of(BigDecimal.ONE, "gems");

            assertThatThrownBy(() -> coins("1").plus(gems))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exchange rate");
            assertThatThrownBy(() -> coins("1").minus(gems))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> coins("1").isAtLeast(gems))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> coins("1").compareTo(gems))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("isAtLeast is inclusive, so an exact balance can pay an exact price")
        void isAtLeastIncludesEquality() {
            // Off by one here means a town with exactly the claim price cannot claim.
            assertThat(coins("5").isAtLeast(coins("5"))).isTrue();
            assertThat(coins("5").isAtLeast(coins("5.0001"))).isFalse();
        }
    }

    @Nested
    @DisplayName("reading and writing")
    class Text {

        @Test
        @DisplayName("a stored amount comes back as the same amount")
        void storageRoundTrips() {
            final Money original = coins("1234.5678");

            assertThat(Money.fromStorage(original.toStorage(), COINS)).isEqualTo(original);
        }

        @Test
        @DisplayName("a player sees money, not a database column")
        void describeIsForPeople() {
            assertThat(coins("12.5000").describe()).isEqualTo("12.5 coins");
            assertThat(coins("100").describe()).isEqualTo("100 coins");
            assertThat(Money.zero(COINS).describe()).isEqualTo("0 coins");
        }

        @Test
        @DisplayName("a typo in a command is empty, not an exception")
        void parseRefusesQuietly() {
            // A player mistyping a deposit should get a message, not a stack trace in the console.
            assertThat(Money.parse("abc", COINS)).isEmpty();
            assertThat(Money.parse("-5", COINS)).isEmpty();
            assertThat(Money.parse("", COINS)).isEmpty();
            assertThat(Money.parse(null, COINS)).isEmpty();
            assertThat(Money.parse(" 2.50 ", COINS)).contains(coins("2.50"));
        }

        @Test
        @DisplayName("scientific notation parses as the number it is, not as a refusal")
        void scientificNotationIsANumber() {
            // Recorded rather than asserted as intent: the javadoc calls "1e9" a typo, and
            // BigDecimal accepts it, so it parses as a billion. Worth knowing before somebody
            // relies on the comment instead of the behaviour.
            assertThat(Money.parse("1e3", COINS)).contains(coins("1000"));
        }
    }
}
