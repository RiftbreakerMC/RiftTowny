package net.riftbreaker.rifttowny.domain.civic;

import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CivicCacheTest {

    private static final ResidentId MAYOR = CivicFixture.resident();
    private static final ResidentId CITIZEN = CivicFixture.resident();
    private static final ResidentId STRANGER = CivicFixture.resident();

    @Nested
    @DisplayName("remembering")
    class Remembering {

        @Test
        @DisplayName("a remembered town answers for itself and its residents")
        void remembersTownAndResidents() {
            final CivicCache cache = CivicCache.empty();
            final Town riftholm = CivicFixture.town("Riftholm", MAYOR, CITIZEN);
            cache.remember(CivicFixture.facts(riftholm));

            assertThat(cache.knows(riftholm.id())).isTrue();
            assertThat(cache.townOf(CITIZEN)).contains(riftholm.id());
            assertThat(cache.townOf(MAYOR)).contains(riftholm.id());
            assertThat(cache.townOf(STRANGER)).isEmpty();
            assertThat(cache.cachedTowns()).isEqualTo(1);
            assertThat(cache.cachedResidents()).isEqualTo(2);
        }

        @Test
        @DisplayName("an unknown town is not silently treated as an absent one")
        void unknownTownIsNotKnown() {
            final CivicCache cache = CivicCache.empty();
            assertThat(cache.knows(TownId.random())).isFalse();
            assertThat(cache.knows(null)).isFalse();
            assertThat(cache.town(TownId.random())).isEmpty();
        }

        @Test
        @DisplayName("re-remembering a town stops its departed residents resolving to it")
        void departedResidentStopsResolving() {
            final CivicCache cache = CivicCache.empty();
            final Town riftholm = CivicFixture.town("Riftholm", MAYOR, CITIZEN);
            cache.remember(CivicFixture.facts(riftholm));

            final Town smaller = riftholm.release(CITIZEN, true).orElseThrow();
            cache.remember(CivicFixture.facts(smaller));

            assertThat(cache.townOf(CITIZEN)).isEmpty();
            assertThat(cache.townOf(MAYOR)).contains(riftholm.id());
            assertThat(cache.cachedResidents()).isEqualTo(1);
        }

        @Test
        @DisplayName("a resident who moved town is not un-indexed by their old town's next update")
        void movingTownSurvivesTheOldTownsUpdate() {
            final CivicCache cache = CivicCache.empty();
            final Town riftholm = CivicFixture.town("Riftholm", MAYOR, CITIZEN);
            final Town ashford = CivicFixture.town("Ashford", CivicFixture.resident());
            cache.remember(CivicFixture.facts(riftholm));

            // The citizen joins Ashford first, then Riftholm's own update lands afterwards. The
            // second update must not remove an index entry that now points somewhere else.
            final Town ashfordWithCitizen = ashford.admit(CITIZEN).orElseThrow();
            cache.remember(CivicFixture.facts(ashfordWithCitizen));
            cache.remember(CivicFixture.facts(riftholm.release(CITIZEN, true).orElseThrow()));

            assertThat(cache.townOf(CITIZEN)).contains(ashford.id());
        }

        @Test
        @DisplayName("trust and nation come from the remembered town")
        void trustAndNation() {
            final CivicCache cache = CivicCache.empty();
            final NationId valen = NationId.random();
            final Town riftholm = CivicFixture.town("Riftholm", MAYOR)
                    .trust(STRANGER).orElseThrow()
                    .joinNation(valen).orElseThrow();
            cache.remember(CivicFixture.facts(riftholm));

            assertThat(cache.isTrusted(riftholm.id(), STRANGER)).isTrue();
            assertThat(cache.isTrusted(riftholm.id(), CITIZEN)).isFalse();
            assertThat(cache.nationOf(riftholm.id())).contains(valen);
            assertThat(cache.nationOfResident(MAYOR)).contains(valen);
            assertThat(cache.nationOfResident(STRANGER)).isEmpty();
        }
    }

    @Nested
    @DisplayName("forgetting")
    class Forgetting {

        @Test
        @DisplayName("a disbanded town leaves its residents townless, not members of a ghost")
        void disbandLeavesResidentsTownless() {
            final CivicCache cache = CivicCache.empty();
            final Town riftholm = CivicFixture.town("Riftholm", MAYOR, CITIZEN);
            cache.remember(CivicFixture.facts(riftholm));

            cache.forget(riftholm.id());

            assertThat(cache.knows(riftholm.id())).isFalse();
            assertThat(cache.townOf(MAYOR)).isEmpty();
            assertThat(cache.townOf(CITIZEN)).isEmpty();
            assertThat(cache.cachedTowns()).isZero();
            assertThat(cache.cachedResidents()).isZero();
        }

        @Test
        @DisplayName("forgetting nothing is not an error")
        void forgettingNothing() {
            final CivicCache cache = CivicCache.empty();
            cache.forget(null);
            cache.forget(TownId.random());
            assertThat(cache.cachedTowns()).isZero();
        }
    }

    @Nested
    @DisplayName("replacing everything")
    class Replacing {

        @Test
        @DisplayName("a reload drops towns that are gone and keeps the ones that are not")
        void reloadReplaces() {
            final CivicCache cache = CivicCache.empty();
            final Town riftholm = CivicFixture.town("Riftholm", MAYOR);
            final Town ashford = CivicFixture.town("Ashford", CITIZEN);
            cache.replaceAll(List.of(CivicFixture.facts(riftholm), CivicFixture.facts(ashford)));

            cache.replaceAll(List.of(CivicFixture.facts(ashford)));

            assertThat(cache.knows(riftholm.id())).isFalse();
            assertThat(cache.knows(ashford.id())).isTrue();
            assertThat(cache.townOf(MAYOR)).isEmpty();
            assertThat(cache.townOf(CITIZEN)).contains(ashford.id());
        }

        @Test
        @DisplayName("a reader never sees a town missing during a reload")
        void reloadIsNotAWindowOfAbsence() throws Exception {
            final CivicCache cache = CivicCache.empty();
            final Town riftholm = CivicFixture.town("Riftholm", MAYOR);
            final TownFacts facts = CivicFixture.facts(riftholm);
            cache.replaceAll(List.of(facts));

            final AtomicBoolean sawAbsence = new AtomicBoolean();
            final AtomicInteger reads = new AtomicInteger();
            final CountDownLatch go = new CountDownLatch(1);
            final Thread reader = new Thread(() -> {
                try {
                    go.await();
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (reads.get() < 20_000) {
                    if (!cache.knows(riftholm.id())) {
                        sawAbsence.set(true);
                    }
                    reads.incrementAndGet();
                }
            });
            reader.start();
            go.countDown();
            for (int i = 0; i < 200; i++) {
                cache.replaceAll(List.of(facts));
            }
            reads.set(20_000);
            reader.join(TimeUnit.SECONDS.toMillis(5));

            assertThat(sawAbsence).isFalse();
        }
    }

    @Test
    @DisplayName("the generation moves on every mutation so a downstream view can notice")
    void generationMoves() {
        final CivicCache cache = CivicCache.empty();
        final long start = cache.generation();
        final Town riftholm = CivicFixture.town("Riftholm", MAYOR);

        cache.remember(CivicFixture.facts(riftholm));
        final long afterRemember = cache.generation();
        cache.forget(riftholm.id());

        assertThat(afterRemember).isGreaterThan(start);
        assertThat(cache.generation()).isGreaterThan(afterRemember);
        assertThat(cache.describe()).isEqualTo("0 town(s), 0 resident(s)");
    }
}
