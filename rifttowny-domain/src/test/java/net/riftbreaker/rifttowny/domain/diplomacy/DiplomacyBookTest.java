package net.riftbreaker.rifttowny.domain.diplomacy;

import net.riftbreaker.rifttowny.domain.org.NationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who has declared what about whom.
 *
 * <p>Almost everything here is about one asymmetry: an alliance takes two nations and an enmity
 * takes one. Getting it backwards in either direction is a real failure — a one-sided alliance
 * would hand somebody the run of your territory on their say-so, and a two-sided enmity would let
 * an aggressor refuse to be at war.</p>
 */
class DiplomacyBookTest {

    private final NationId valen = NationId.random();
    private final NationId ashmark = NationId.random();
    private final NationId highmarch = NationId.random();

    private DiplomacyBook book;

    @BeforeEach
    void setUp() {
        book = DiplomacyBook.empty();
    }

    private void declare(final NationId declarer, final Relation relation, final NationId target) {
        book.declare(new DiplomacyBook.Declaration(declarer, relation, target));
    }

    @Nested
    @DisplayName("alliances take two")
    class Alliances {

        @Test
        @DisplayName("one nation declaring it is not an alliance")
        void oneSidedIsNotAnAlliance() {
            // The ALLY rung grants real access to territory. If this were enough, any nation could
            // let itself into another's land by typing a command the other never saw.
            declare(valen, Relation.ALLY, ashmark);

            assertThat(book.areAllied(valen, ashmark)).isFalse();
            assertThat(book.areAllied(ashmark, valen)).isFalse();
            assertThat(book.offeredAlliances(valen)).containsExactly(ashmark);
        }

        @Test
        @DisplayName("both declaring it is")
        void mutualIsAnAlliance() {
            declare(valen, Relation.ALLY, ashmark);
            declare(ashmark, Relation.ALLY, valen);

            assertThat(book.areAllied(valen, ashmark)).isTrue();
            assertThat(book.areAllied(ashmark, valen)).isTrue();
            assertThat(book.allies(valen)).containsExactly(ashmark);
            assertThat(book.offeredAlliances(valen)).isEmpty();
        }

        @Test
        @DisplayName("either side withdrawing ends it")
        void withdrawingEndsIt() {
            declare(valen, Relation.ALLY, ashmark);
            declare(ashmark, Relation.ALLY, valen);

            book.withdraw(new DiplomacyBook.Declaration(ashmark, Relation.ALLY, valen));

            assertThat(book.areAllied(valen, ashmark)).isFalse();
            // Valen's own offer stands - it withdrew nothing.
            assertThat(book.offeredAlliances(valen)).containsExactly(ashmark);
        }

        @Test
        @DisplayName("an alliance is not transitive")
        void alliesOfAlliesAreNotAllies() {
            declare(valen, Relation.ALLY, ashmark);
            declare(ashmark, Relation.ALLY, valen);
            declare(ashmark, Relation.ALLY, highmarch);
            declare(highmarch, Relation.ALLY, ashmark);

            assertThat(book.areAllied(valen, highmarch)).isFalse();
        }
    }

    @Nested
    @DisplayName("enmities take one")
    class Enmities {

        @Test
        @DisplayName("declaring an enemy needs nobody's agreement")
        void oneSidedIsEnough() {
            // Refusing to be somebody's enemy is not a thing you can do.
            declare(valen, Relation.ENEMY, ashmark);

            assertThat(book.isEnemy(valen, ashmark)).isTrue();
            assertThat(book.hostile(valen, ashmark)).isTrue();
        }

        @Test
        @DisplayName("it binds the declarer, not the target")
        void itIsDirectional() {
            declare(valen, Relation.ENEMY, ashmark);

            // Ashmark has declared nothing, and the book does not put words in its mouth.
            assertThat(book.isEnemy(ashmark, valen)).isFalse();
            assertThat(book.declared(ashmark, Relation.ENEMY)).isEmpty();
        }

        @Test
        @DisplayName("either side declaring makes the pair hostile")
        void hostilityIsSymmetricEvenWhenTheDeclarationIsNot() {
            declare(ashmark, Relation.ENEMY, valen);

            assertThat(book.hostile(valen, ashmark)).isTrue();
            assertThat(book.hostile(ashmark, valen)).isTrue();
        }
    }

    @Nested
    @DisplayName("housekeeping")
    class Housekeeping {

        @Test
        @DisplayName("declaring twice is the same as declaring once")
        void declarationsAreIdempotent() {
            declare(valen, Relation.ALLY, ashmark);
            declare(valen, Relation.ALLY, ashmark);

            assertThat(book.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("a nation cannot declare anything about itself")
        void selfDeclarationsAreRefused() {
            assertThatThrownBy(() ->
                    new DiplomacyBook.Declaration(valen, Relation.ALLY, valen))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a dissolved nation is forgotten in both directions")
        void forgettingClearsBothSides() {
            // Otherwise a dead nation stays somebody's ally, and the nations that had declared it
            // an enemy are left carrying a grudge against nothing.
            declare(valen, Relation.ALLY, ashmark);
            declare(ashmark, Relation.ALLY, valen);
            declare(highmarch, Relation.ENEMY, ashmark);

            book.forget(ashmark);

            assertThat(book.areAllied(valen, ashmark)).isFalse();
            assertThat(book.declared(valen, Relation.ALLY)).isEmpty();
            assertThat(book.isEnemy(highmarch, ashmark)).isFalse();
            assertThat(book.size()).isZero();
        }

        @Test
        @DisplayName("a startup load replaces everything")
        void loadReplaces() {
            declare(valen, Relation.ENEMY, ashmark);

            book.replaceAll(List.of(
                    new DiplomacyBook.Declaration(valen, Relation.ALLY, highmarch),
                    new DiplomacyBook.Declaration(highmarch, Relation.ALLY, valen)));

            assertThat(book.isEnemy(valen, ashmark)).isFalse();
            assertThat(book.areAllied(valen, highmarch)).isTrue();
        }

        @Test
        @DisplayName("nothing is allied with nothing")
        void nullsAndSelvesAreSafe() {
            assertThat(book.areAllied(null, valen)).isFalse();
            assertThat(book.areAllied(valen, valen)).isFalse();
            assertThat(book.isEnemy(null, null)).isFalse();
            assertThat(book.allies(null)).isEmpty();
        }

        @Test
        @DisplayName("a relation is named however it is typed")
        void parsing() {
            assertThat(Relation.parse("ally")).contains(Relation.ALLY);
            assertThat(Relation.parse("ALLIES")).contains(Relation.ALLY);
            assertThat(Relation.parse(" enemy ")).contains(Relation.ENEMY);
            assertThat(Relation.parse("neutral")).isEmpty();
            assertThat(Relation.parse(null)).isEmpty();
        }
    }
}
