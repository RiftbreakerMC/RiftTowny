package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.api.ChunkKey;
import org.junit.jupiter.api.DisplayName;
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
}
