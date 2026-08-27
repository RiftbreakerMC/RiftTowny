package net.riftbreaker.rifttowny.domain.justice;

import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private static final ResidentId OFFICER = ResidentId.of(UUID.randomUUID());
    private static final java.time.Instant WHEN =
            java.time.Instant.parse("2026-03-04T10:15:30Z");

    /**
     * Declares with a fixed officer and moment, for the cases that are about the ladder rather than
     * the provenance. {@link Provenance} is where the other two components are actually asserted,
     * so this shorthand cannot quietly stop covering them.
     */
    private void declare(final TownId town, final ResidentId who) {
        book.declare(town, who, OFFICER, WHEN);
    }

    @Nested
    @DisplayName("declaring")
    class Declaring {

        @Test
        @DisplayName("binds one town and one player, and nobody else")
        void isNarrow() {
            declare(ASHFORD, BEDE);

            assertThat(book.isOutlawed(ASHFORD, BEDE)).isTrue();
            // Not the other player, not the other town, and not the reverse pairing.
            assertThat(book.isOutlawed(ASHFORD, ADA)).isFalse();
            assertThat(book.isOutlawed(HIGHHOLM, BEDE)).isFalse();
        }

        @Test
        @DisplayName("twice is the same as once")
        void isIdempotent() {
            declare(ASHFORD, BEDE);
            declare(ASHFORD, BEDE);

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
            declare(ASHFORD, BEDE);
            declare(ASHFORD, ADA);

            book.pardon(ASHFORD, BEDE);

            assertThat(book.isOutlawed(ASHFORD, BEDE)).isFalse();
            assertThat(book.isOutlawed(ASHFORD, ADA)).isTrue();
        }

        @Test
        @DisplayName("pardoning somebody who was never outlawed changes nothing")
        void pardoningNothing() {
            declare(ASHFORD, ADA);

            book.pardon(ASHFORD, BEDE);
            book.pardon(HIGHHOLM, ADA);

            assertThat(book.size()).isEqualTo(1);
            assertThat(book.isOutlawed(ASHFORD, ADA)).isTrue();
        }

        @Test
        @DisplayName("a town that has pardoned everybody stops being walked")
        void emptyTownsAreDropped() {
            // Otherwise townsOutlawing walks a growing list of towns holding nothing.
            declare(ASHFORD, BEDE);
            book.pardon(ASHFORD, BEDE);

            assertThat(book.size()).isZero();
            assertThat(book.describe()).contains("towns=0");
        }

        @Test
        @DisplayName("a disbanded town's grudges go with it")
        void forgetting() {
            declare(ASHFORD, BEDE);
            declare(HIGHHOLM, BEDE);

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
            declare(ASHFORD, BEDE);
            declare(ASHFORD, ADA);
            declare(HIGHHOLM, BEDE);

            assertThat(book.of(ASHFORD)).containsExactlyInAnyOrder(BEDE, ADA);
            assertThat(book.townsOutlawing(BEDE)).containsExactlyInAnyOrder(ASHFORD, HIGHHOLM);
            assertThat(book.townsOutlawing(ADA)).containsExactly(ASHFORD);
        }

        @Test
        @DisplayName("a town's list is a copy, so a caller cannot edit the book through it")
        void listsAreCopies() {
            declare(ASHFORD, BEDE);

            final var listed = book.of(ASHFORD);

            assertThat(listed).isUnmodifiable();
        }

        @Test
        @DisplayName("a startup load replaces everything")
        void loadReplaces() {
            declare(ASHFORD, BEDE);

            book.replaceAll(List.of(new Outlaws.Declaration(HIGHHOLM, ADA, OFFICER, WHEN)));

            assertThat(book.isOutlawed(ASHFORD, BEDE)).isFalse();
            assertThat(book.isOutlawed(HIGHHOLM, ADA)).isTrue();
            assertThat(book.size()).isEqualTo(1);
        }
    }

    /**
     * The officer and the moment behind a declaration.
     *
     * <p>These two columns were written on every row from V14 and read back by nothing, so the
     * migration's own reason for them — "which of my officers did this, and when" is the first
     * question a mayor faces when a player appeals — could not be answered. The book dropped them
     * on the way in, keeping a bare set of ids.</p>
     */
    @Nested
    @DisplayName("provenance")
    class Provenance {

        @Test
        @DisplayName("survives being put into the book")
        void isKept() {
            book.declare(ASHFORD, BEDE, OFFICER, WHEN);

            assertThat(book.declarationsOf(ASHFORD)).singleElement().satisfies(one -> {
                assertThat(one.who()).isEqualTo(BEDE);
                assertThat(one.author()).contains(OFFICER);
                assertThat(one.declaredAt()).isEqualTo(WHEN);
            });
        }

        @Test
        @DisplayName("records nobody in particular for the console and for imports")
        void allowsNoAuthor() {
            // Truer than naming whoever happened to be mayor at the time, which is what a
            // non-null-by-default column would have forced.
            book.declare(ASHFORD, BEDE, null, WHEN);

            assertThat(book.declarationsOf(ASHFORD)).singleElement()
                    .satisfies(one -> assertThat(one.author()).isEmpty());
        }

        @Test
        @DisplayName("re-declaring overwrites rather than duplicating")
        void isStillIdempotent() {
            final java.time.Instant later = WHEN.plusSeconds(3600);
            book.declare(ASHFORD, BEDE, OFFICER, WHEN);
            book.declare(ASHFORD, BEDE, ADA, later);

            assertThat(book.declarationsOf(ASHFORD)).singleElement()
                    .satisfies(one -> assertThat(one.declaredAt()).isEqualTo(later));
            assertThat(book.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("a pardoned declaration takes its provenance with it")
        void goesOnPardon() {
            book.declare(ASHFORD, BEDE, OFFICER, WHEN);
            book.pardon(ASHFORD, BEDE);

            assertThat(book.declarationsOf(ASHFORD)).isEmpty();
        }

        @Test
        @DisplayName("the listing is a copy, so a caller cannot edit the book through it")
        void isACopy() {
            book.declare(ASHFORD, BEDE, OFFICER, WHEN);

            final var listed = book.declarationsOf(ASHFORD);
            assertThatThrownBy(() -> listed.clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
