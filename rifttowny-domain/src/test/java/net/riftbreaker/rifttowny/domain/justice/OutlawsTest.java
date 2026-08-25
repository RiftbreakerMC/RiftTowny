package net.riftbreaker.rifttowny.domain.justice;

import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outlaw book.
 *
 * <p>Read on the protection path, so what matters is that it answers the one hot question correctly
 * for every combination of town and player, and that a lifted outlawry really stops answering.</p>
 */
class OutlawsTest {

    private final Outlaws book = Outlaws.empty();

    private static final TownId ASHFORD = TownId.random();
    private static final TownId HIGHHOLM = TownId.random();
    private static final ResidentId BEDE = ResidentId.of(UUID.randomUUID());
    private static final ResidentId ADA = ResidentId.of(UUID.randomUUID());

    @Nested
    @DisplayName("declaring")
    class Declaring {

        @Test
        @DisplayName("binds one town and one player, and nobody else")
        void isNarrow() {
            book.declare(ASHFORD, BEDE);

            assertThat(book.isOutlawed(ASHFORD, BEDE)).isTrue();
            // Not the other player, not the other town, and not the reverse pairing.
            assertThat(book.isOutlawed(ASHFORD, ADA)).isFalse();
            assertThat(book.isOutlawed(HIGHHOLM, BEDE)).isFalse();
        }

        @Test
        @DisplayName("twice is the same as once")
        void isIdempotent() {
            book.declare(ASHFORD, BEDE);
            book.declare(ASHFORD, BEDE);

            assertThat(book.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("nothing is outlawed by nothing")
        void nullsAreSafe() {
            // Protection calls this with whatever it has, and wilderness has no town.
            assertThat(book.isOutlawed(null, BEDE)).isFalse();
            assertThat(book.isOutlawed(ASHFORD, null)).isFalse();
            assertThat(book.of(null)).isEmpty();
            assertThat(book.townsOutlawing(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("lifting")
    class Lifting {

        @Test
        @DisplayName("a pardon really stops answering")
        void pardonWorks() {
            book.declare(ASHFORD, BEDE);
            book.declare(ASHFORD, ADA);

            book.pardon(ASHFORD, BEDE);

            assertThat(book.isOutlawed(ASHFORD, BEDE)).isFalse();
            assertThat(book.isOutlawed(ASHFORD, ADA)).isTrue();
        }

        @Test
        @DisplayName("pardoning somebody who was never outlawed changes nothing")
        void pardoningNothing() {
            book.declare(ASHFORD, ADA);

            book.pardon(ASHFORD, BEDE);
            book.pardon(HIGHHOLM, ADA);

            assertThat(book.size()).isEqualTo(1);
            assertThat(book.isOutlawed(ASHFORD, ADA)).isTrue();
        }

        @Test
        @DisplayName("a town that has pardoned everybody stops being walked")
        void emptyTownsAreDropped() {
            // Otherwise townsOutlawing walks a growing list of towns holding nothing.
            book.declare(ASHFORD, BEDE);
            book.pardon(ASHFORD, BEDE);

            assertThat(book.size()).isZero();
            assertThat(book.describe()).contains("towns=0");
        }

        @Test
        @DisplayName("a disbanded town's grudges go with it")
        void forgetting() {
            book.declare(ASHFORD, BEDE);
            book.declare(HIGHHOLM, BEDE);

            book.forget(ASHFORD);

            assertThat(book.isOutlawed(ASHFORD, BEDE)).isFalse();
            assertThat(book.isOutlawed(HIGHHOLM, BEDE)).isTrue();
        }
    }

    @Nested
    @DisplayName("the two listings")
    class Listings {

        @Test
        @DisplayName("a town's own list, and where one player is unwelcome")
        void bothDirections() {
            book.declare(ASHFORD, BEDE);
            book.declare(ASHFORD, ADA);
            book.declare(HIGHHOLM, BEDE);

            assertThat(book.of(ASHFORD)).containsExactlyInAnyOrder(BEDE, ADA);
            assertThat(book.townsOutlawing(BEDE)).containsExactlyInAnyOrder(ASHFORD, HIGHHOLM);
            assertThat(book.townsOutlawing(ADA)).containsExactly(ASHFORD);
        }

        @Test
        @DisplayName("a town's list is a copy, so a caller cannot edit the book through it")
        void listsAreCopies() {
            book.declare(ASHFORD, BEDE);

            final var listed = book.of(ASHFORD);

            assertThat(listed).isUnmodifiable();
        }

        @Test
        @DisplayName("a startup load replaces everything")
        void loadReplaces() {
            book.declare(ASHFORD, BEDE);

            book.replaceAll(List.of(new Outlaws.Declaration(HIGHHOLM, ADA)));

            assertThat(book.isOutlawed(ASHFORD, BEDE)).isFalse();
            assertThat(book.isOutlawed(HIGHHOLM, ADA)).isTrue();
            assertThat(book.size()).isEqualTo(1);
        }
    }
}
