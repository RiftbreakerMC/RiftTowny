package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.api.ChunkKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The block-to-chunk conversion, which is the one piece of arithmetic in the protection listeners
 * that can be wrong without anybody noticing until half a world is unprotected.
 */
class ChunksTest {

    private static final UUID WORLD = UUID.randomUUID();

    @Test
    @DisplayName("positive coordinates land in the expected chunk")
    void positiveCoordinates() {
        assertThat(Chunks.fromBlock(WORLD, 0, 0)).isEqualTo(new ChunkKey(WORLD, 0, 0));
        assertThat(Chunks.fromBlock(WORLD, 15, 15)).isEqualTo(new ChunkKey(WORLD, 0, 0));
        assertThat(Chunks.fromBlock(WORLD, 16, 16)).isEqualTo(new ChunkKey(WORLD, 1, 1));
        assertThat(Chunks.fromBlock(WORLD, 1_000, 2_000)).isEqualTo(new ChunkKey(WORLD, 62, 125));
    }

    @Test
    @DisplayName("negative coordinates round down, not toward zero")
    void negativeCoordinates() {
        // The trap: -1 / 16 is 0, which is the chunk next door. Block -1 is in chunk -1.
        assertThat(Chunks.fromBlock(WORLD, -1, -1)).isEqualTo(new ChunkKey(WORLD, -1, -1));
        assertThat(Chunks.fromBlock(WORLD, -16, -16)).isEqualTo(new ChunkKey(WORLD, -1, -1));
        assertThat(Chunks.fromBlock(WORLD, -17, -17)).isEqualTo(new ChunkKey(WORLD, -2, -2));
        assertThat(Chunks.fromBlock(WORLD, -1_000, -2_000)).isEqualTo(new ChunkKey(WORLD, -63, -125));
    }

    @Test
    @DisplayName("every block of one chunk maps to that chunk, on both sides of the origin")
    void everyBlockOfAChunk() {
        for (int offset = 0; offset < 16; offset++) {
            assertThat(Chunks.fromBlock(WORLD, offset, offset))
                    .as("block %d", offset)
                    .isEqualTo(new ChunkKey(WORLD, 0, 0));
            assertThat(Chunks.fromBlock(WORLD, -16 + offset, -16 + offset))
                    .as("block %d", -16 + offset)
                    .isEqualTo(new ChunkKey(WORLD, -1, -1));
        }
    }

    @Test
    @DisplayName("the world is part of the key, so two worlds never share a chunk")
    void worldsAreDistinct() {
        assertThat(Chunks.fromBlock(WORLD, 0, 0))
                .isNotEqualTo(Chunks.fromBlock(UUID.randomUUID(), 0, 0));
    }
    /**
     * Which chunks a piston move puts at stake.
     *
     * <p>The one place in protection where a chunk boundary is crossed by arithmetic rather than by
     * somebody walking over it, and the cost of getting it wrong runs both ways: a piston that
     * grabs land it should not, or one that stops working inside a town's own walls.</p>
     */
    @Nested
    @DisplayName("what a piston move touches")
    class PistonReach {

        @Test
        @DisplayName("a retraction puts only the block's own chunk at stake")
        void retractionChecksOriginOnly() {
            // A zero delta is how a retraction is passed in: getDirection reports the piston's
            // facing while the pulled blocks travel the other way, so a destination computed from
            // it would be wrong in a direction nothing would notice.
            //
            // This holds with or without the zero-delta short circuit in touched: with no delta
            // the destination is the origin, and the deduplication answers the same. Deleting that
            // branch is an equivalent mutation, worth knowing before somebody reads its survival
            // as a gap here.
            final ChunkKey origin = Chunks.fromBlock(WORLD, 40, 40);

            assertThat(WorldProtectionListener.touched(origin, 40, 40, 0, 0))
                    .containsExactly(origin);
        }

        @Test
        @DisplayName("a push that stays inside one chunk names it once, not twice")
        void insideOneChunk() {
            final ChunkKey origin = Chunks.fromBlock(WORLD, 40, 40);

            assertThat(WorldProtectionListener.touched(origin, 40, 40, 1, 0))
                    .containsExactly(origin);
        }

        @Test
        @DisplayName("a push over the border names the chunk it lands in as well")
        void acrossTheBorder() {
            // The attack this exists for: a block one column inside a claim, pushed out of it.
            final ChunkKey origin = Chunks.fromBlock(WORLD, 15, 0);

            assertThat(WorldProtectionListener.touched(origin, 15, 0, 1, 0))
                    .containsExactly(origin, Chunks.fromBlock(WORLD, 16, 0));
        }

        @Test
        @DisplayName("and on the negative side of the origin, where a division would be wrong")
        void acrossTheBorderNegative() {
            // -1 >> 4 is -1; -1 / 16 is 0. A piston at the western edge of the world would push
            // into a chunk the check had already cleared, and the block would land in a claim
            // nobody asked about.
            final ChunkKey origin = Chunks.fromBlock(WORLD, 0, 0);

            assertThat(WorldProtectionListener.touched(origin, 0, 0, -1, 0))
                    .containsExactly(origin, Chunks.fromBlock(WORLD, -1, 0));
        }

        @Test
        @DisplayName("a push along z crosses the same way")
        void acrossTheBorderOnZ() {
            final ChunkKey origin = Chunks.fromBlock(WORLD, 0, 15);

            assertThat(WorldProtectionListener.touched(origin, 0, 15, 0, 1))
                    .containsExactly(origin, Chunks.fromBlock(WORLD, 0, 16));
        }

        @Test
        @DisplayName("the destination stays in the block's own world")
        void destinationKeepsTheWorld() {
            final ChunkKey origin = Chunks.fromBlock(WORLD, 15, 0);

            assertThat(WorldProtectionListener.touched(origin, 15, 0, 1, 0))
                    .allSatisfy(chunk -> assertThat(chunk.worldId()).isEqualTo(WORLD));
        }
    }

}
