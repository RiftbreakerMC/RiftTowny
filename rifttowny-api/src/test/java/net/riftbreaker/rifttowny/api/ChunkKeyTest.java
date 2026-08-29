package net.riftbreaker.rifttowny.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key everything about territory is decided on.
 *
 * <p>This module had no tests at all, which is the wrong way round: it is the part other plugins
 * compile against, so it is the part whose behaviour cannot be quietly adjusted later. And
 * {@link ChunkKey#isAdjacentTo} is not a convenience — claim contiguity, the rule that an outpost
 * must <em>not</em> touch its town, and the piston border check all resolve to it. It was exercised
 * through those, never asserted on its own.
 */
class ChunkKeyTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER_WORLD = UUID.randomUUID();

    private static ChunkKey at(final int x, final int z) {
        return new ChunkKey(WORLD, x, z);
    }

    @Nested
    @DisplayName("what counts as next to")
    class Adjacency {

        @Test
        @DisplayName("the four orthogonal neighbours, and only those")
        void orthogonalOnly() {
            final ChunkKey middle = at(0, 0);

            assertThat(middle.isAdjacentTo(at(1, 0))).isTrue();
            assertThat(middle.isAdjacentTo(at(-1, 0))).isTrue();
            assertThat(middle.isAdjacentTo(at(0, 1))).isTrue();
            assertThat(middle.isAdjacentTo(at(0, -1))).isTrue();
        }

        @Test
        @DisplayName("a diagonal is not adjacent, because you cannot walk across a corner")
        void diagonalsAreNot() {
            // The rule the whole territory model rests on: two claims meeting at a corner look
            // joined on a map and share no edge, so a town built that way could not be walked
            // across without leaving its own land.
            assertThat(at(0, 0).isAdjacentTo(at(1, 1))).isFalse();
            assertThat(at(0, 0).isAdjacentTo(at(-1, -1))).isFalse();
        }

        @Test
        @DisplayName("a chunk is not adjacent to itself")
        void notItself() {
            // Distance zero, not one. If this answered true, a first ordinary claim would satisfy
            // "must touch the town" against nothing but itself.
            assertThat(at(4, 4).isAdjacentTo(at(4, 4))).isFalse();
        }

        @Test
        @DisplayName("two chunks apart is not adjacent")
        void twoApartIsNot() {
            assertThat(at(0, 0).isAdjacentTo(at(2, 0))).isFalse();
        }

        @Test
        @DisplayName("nothing in another world is ever adjacent")
        void worldsDoNotTouch() {
            // Coordinates repeat in every world, so without the world check a town in the nether
            // would extend a town in the overworld.
            assertThat(at(0, 0).isAdjacentTo(new ChunkKey(OTHER_WORLD, 1, 0))).isFalse();
            assertThat(at(0, 0).isAdjacentTo(new ChunkKey(OTHER_WORLD, 0, 0))).isFalse();
        }

        @Test
        @DisplayName("null is not adjacent, rather than an exception")
        void nullIsNotAdjacent() {
            assertThat(at(0, 0).isAdjacentTo(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("moving and identity")
    class Movement {

        @Test
        @DisplayName("an offset lands where it says, and stays in its world")
        void offsetMoves() {
            assertThat(at(3, 4).offset(-1, 2)).isEqualTo(at(2, 6));
            assertThat(at(3, 4).offset(1, 0).worldId()).isEqualTo(WORLD);
        }

        @Test
        @DisplayName("an offset of nothing is the same chunk")
        void offsetOfNothing() {
            assertThat(at(3, 4).offset(0, 0)).isEqualTo(at(3, 4));
        }

        @Test
        @DisplayName("the world is part of identity, so two worlds never share a chunk")
        void worldIsPartOfIdentity() {
            // Protection is answered from maps keyed on this. Two worlds colliding here would let
            // one town's flags decide another town's land.
            assertThat(at(0, 0)).isNotEqualTo(new ChunkKey(OTHER_WORLD, 0, 0));
            assertThat(at(0, 0)).isEqualTo(at(0, 0));
            assertThat(at(0, 0).hashCode()).isEqualTo(at(0, 0).hashCode());
        }

        @Test
        @DisplayName("a key needs a world")
        void worldIsRequired() {
            assertThatThrownBy(() -> new ChunkKey(null, 0, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("it reads as world:x,z, which is what appears in logs and keys")
        void readable() {
            assertThat(at(2, -3)).hasToString(WORLD + ":2,-3");
        }
    }
}
