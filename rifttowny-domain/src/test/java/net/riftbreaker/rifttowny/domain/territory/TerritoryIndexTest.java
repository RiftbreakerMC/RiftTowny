package net.riftbreaker.rifttowny.domain.territory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TerritoryIndexTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final UUID WORLD = UUID.randomUUID();
    private static final TownId RIFTHOLM = TownId.random();
    private static final TownId ASHFORD = TownId.random();

    private static ChunkKey at(final int x, final int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private static Claim claim(final TownId town, final int x, final int z) {
        return Claim.of(at(x, z), town, ClaimKind.ORDINARY, NOW);
    }

    @Test
    @DisplayName("an empty index reports everything as wilderness")
    void emptyIndexIsAllWilderness() {
        final TerritoryIndex index = TerritoryIndex.empty();

        assertThat(index.isWilderness(at(0, 0))).isTrue();
        assertThat(index.at(at(0, 0))).isEmpty();
        assertThat(index.ownerOf(at(0, 0))).isEmpty();
        assertThat(index.size()).isZero();
    }

    @Test
    @DisplayName("a claim is found by its chunk, and only its chunk")
    void lookupIsExact() {
        final TerritoryIndex index = TerritoryIndex.empty();
        index.put(claim(RIFTHOLM, 1, 1));

        assertThat(index.ownerOf(at(1, 1))).contains(RIFTHOLM);
        assertThat(index.isWilderness(at(1, 1))).isFalse();
        assertThat(index.isWilderness(at(1, 2))).isTrue();
        assertThat(index.isOwnedBy(at(1, 1), RIFTHOLM)).isTrue();
        assertThat(index.isOwnedBy(at(1, 1), ASHFORD)).isFalse();
    }

    @Test
    @DisplayName("a chunk in another world is a different chunk")
    void worldsAreSeparate() {
        final TerritoryIndex index = TerritoryIndex.empty();
        index.put(claim(RIFTHOLM, 0, 0));

        assertThat(index.isWilderness(new ChunkKey(UUID.randomUUID(), 0, 0))).isTrue();
    }

    @Test
    @DisplayName("a released chunk becomes wilderness again")
    void removalClearsTheChunk() {
        final TerritoryIndex index = TerritoryIndex.empty();
        index.put(claim(RIFTHOLM, 2, 2));

        index.remove(at(2, 2));

        assertThat(index.isWilderness(at(2, 2))).isTrue();
        assertThat(index.size()).isZero();
    }

    @Test
    @DisplayName("disbanding a town clears its territory and leaves everyone else's alone")
    void removingATownIsScoped() {
        final TerritoryIndex index = TerritoryIndex.empty();
        index.put(claim(RIFTHOLM, 0, 0));
        index.put(claim(RIFTHOLM, 0, 1));
        index.put(claim(ASHFORD, 9, 9));

        final int removed = index.removeAllOf(RIFTHOLM);

        assertThat(removed).isEqualTo(2);
        assertThat(index.isWilderness(at(0, 0))).isTrue();
        assertThat(index.ownerOf(at(9, 9))).contains(ASHFORD);
    }

    @Test
    @DisplayName("a reload replaces the set without ever reading as empty")
    void replaceAllSwapsRatherThanClears() {
        final TerritoryIndex index = TerritoryIndex.empty();
        index.put(claim(RIFTHOLM, 0, 0));
        index.put(claim(RIFTHOLM, 5, 5));

        index.replaceAll(List.of(claim(ASHFORD, 0, 0), claim(ASHFORD, 1, 0)));

        assertThat(index.ownerOf(at(0, 0)))
                .as("the chunk changed hands rather than briefly becoming wilderness")
                .contains(ASHFORD);
        assertThat(index.isWilderness(at(5, 5))).isTrue();
        assertThat(index.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a reader never sees the index empty while it is being replaced")
    void replaceAllIsNotVisiblyEmpty() throws Exception {
        final TerritoryIndex index = TerritoryIndex.empty();
        index.replaceAll(List.of(claim(RIFTHOLM, 0, 0)));

        final AtomicBoolean sawWilderness = new AtomicBoolean();
        final AtomicBoolean stop = new AtomicBoolean();
        final CountDownLatch started = new CountDownLatch(1);
        final ExecutorService reader = Executors.newSingleThreadExecutor();
        reader.submit(() -> {
            started.countDown();
            while (!stop.get()) {
                // The chunk is owned before and after every replacement below, so any observation
                // of wilderness would mean a reader caught the index mid-clear - which is exactly
                // the window in which a griefer would be allowed to break a block.
                if (index.isWilderness(at(0, 0))) {
                    sawWilderness.set(true);
                }
            }
        });
        started.await(2, TimeUnit.SECONDS);

        for (int round = 0; round < 200; round++) {
            index.replaceAll(List.of(claim(RIFTHOLM, 0, 0), claim(ASHFORD, round + 1, 0)));
        }
        stop.set(true);
        reader.shutdown();
        assertThat(reader.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(sawWilderness).isFalse();
    }

    @Test
    @DisplayName("the generation moves on every change, so a downstream cache can notice")
    void generationTracksMutations() {
        final TerritoryIndex index = TerritoryIndex.empty();
        final long start = index.generation();

        index.put(claim(RIFTHOLM, 0, 0));
        final long afterPut = index.generation();
        index.remove(at(0, 0));

        assertThat(afterPut).isGreaterThan(start);
        assertThat(index.generation()).isGreaterThan(afterPut);
    }

    @Test
    @DisplayName("statistics distinguish lookups inside a claim from lookups in the open")
    void statisticsCountBothOutcomes() {
        final TerritoryIndex index = TerritoryIndex.empty();
        index.put(claim(RIFTHOLM, 0, 0));

        index.at(at(0, 0));
        index.at(at(1, 1));
        index.at(at(2, 2));

        final TerritoryIndex.Statistics statistics = index.statistics();
        assertThat(statistics.hits()).isEqualTo(1);
        assertThat(statistics.misses()).isEqualTo(2);
        assertThat(statistics.lookups()).isEqualTo(3);
        assertThat(statistics.describe()).contains("1 claim(s)");
    }

    @Test
    @DisplayName("a null chunk is wilderness rather than an exception")
    void nullsAreWilderness() {
        final TerritoryIndex index = TerritoryIndex.empty();

        assertThat(index.isWilderness(null)).isTrue();
        assertThat(index.at(null)).isEmpty();
        assertThat(index.isOwnedBy(null, RIFTHOLM)).isFalse();
    }

    @Test
    @DisplayName("counting a town's chunks is available, and named so it stays out of listeners")
    void countingIsAvailableButMarked() {
        final TerritoryIndex index = TerritoryIndex.empty();
        index.put(claim(RIFTHOLM, 0, 0));
        index.put(claim(RIFTHOLM, 1, 0));
        index.put(claim(ASHFORD, 9, 9));

        assertThat(index.countForTownScanning(RIFTHOLM)).isEqualTo(2);
    }
}
