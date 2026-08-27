package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.justice.Outlaws;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.OutlawService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outlawry, through real storage.
 *
 * <p>The domain test covers the book. What only a database shows is that the row and the cache agree
 * afterwards, that a refusal leaves neither changed, and that a disbanded town's grudges really go —
 * the last of which is the failure that let a dissolved nation stay somebody's ally until restart.</p>
 */
class OutlawServiceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());
    private static final ResidentId STRANGER = ResidentId.of(UUID.randomUUID());

    private final CivicCache civicCache = CivicCache.empty();
    private final Outlaws book = Outlaws.empty();

    private JdbcCivicStore store;
    private OutlawService outlaws;
    private TownService towns;
    private CivicCacheService civic;
    private Town ashford;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        civic = new CivicCacheService(store, civicCache,
                net.riftbreaker.rifttowny.domain.civic.NationCache.empty(),
                net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook.empty(),
                book, warning -> { });
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, TerritoryIndex.empty(), civic);
        outlaws = new OutlawService(store, CLOCK, book);

        final JdbcResidentRepository residents = new JdbcResidentRepository(database, DIRECT);
        residents.save(Resident.newcomer(MAYOR, "Bede", NOW)).join();
        residents.save(Resident.newcomer(CITIZEN, "Ada", NOW)).join();
        residents.save(Resident.newcomer(STRANGER, "Cato", NOW)).join();
        ashford = towns.found(MAYOR, "Bede", "Ashford").join().value().orElseThrow();
        towns.join(MAYOR, CITIZEN, ashford.id()).join();
    }

    @Nested
    @DisplayName("declaring")
    class Declaring {

        @Test
        @DisplayName("writes the row and tells the cache")
        void writesAndCaches() {
            assertThat(outlaws.declare(MAYOR, ashford.id(), STRANGER).join().succeeded()).isTrue();

            assertThat(book.isOutlawed(ashford.id(), STRANGER)).isTrue();
            assertThat(store.inTransaction(t -> t.outlaws().holds(ashford.id(), STRANGER)).join())
                    .isTrue();
        }

        @Test
        @DisplayName("a resident of the town is refused, and nothing is written")
        void residentsCannotBeOutlawed() {
            final var refused = outlaws.declare(MAYOR, ashford.id(), CITIZEN).join();

            assertThat(refused.denial()).contains(ChangeDenial.CANNOT_OUTLAW_A_RESIDENT);
            assertThat(book.isOutlawed(ashford.id(), CITIZEN)).isFalse();
            assertThat(store.inTransaction(t -> t.outlaws().holds(ashford.id(), CITIZEN)).join())
                    .isFalse();
        }

        @Test
        @DisplayName("twice is refused rather than silently doing nothing")
        void duplicatesAreRefused() {
            outlaws.declare(MAYOR, ashford.id(), STRANGER).join();

            assertThat(outlaws.declare(MAYOR, ashford.id(), STRANGER).join().denial())
                    .contains(ChangeDenial.ALREADY_OUTLAWED);
        }

        @Test
        @DisplayName("somebody with no standing in the town cannot")
        void needsThePermission() {
            final var refused = outlaws.declare(STRANGER, ashford.id(), CITIZEN).join();

            assertThat(refused.denial()).contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(book.size()).isZero();
        }
    }

    @Nested
    @DisplayName("pardoning")
    class Pardoning {

        @Test
        @DisplayName("removes the row and tells the cache")
        void removesAndUncaches() {
            outlaws.declare(MAYOR, ashford.id(), STRANGER).join();

            assertThat(outlaws.pardon(MAYOR, ashford.id(), STRANGER).join().succeeded()).isTrue();

            assertThat(book.isOutlawed(ashford.id(), STRANGER)).isFalse();
            assertThat(store.inTransaction(t -> t.outlaws().holds(ashford.id(), STRANGER)).join())
                    .isFalse();
        }

        @Test
        @DisplayName("pardoning somebody who was never outlawed is refused")
        void nothingToPardon() {
            assertThat(outlaws.pardon(MAYOR, ashford.id(), STRANGER).join().denial())
                    .contains(ChangeDenial.NOT_OUTLAWED);
        }
    }

    @Nested
    @DisplayName("afterwards")
    class Afterwards {

        @Test
        @DisplayName("a startup load rebuilds the cache from the table")
        void loadRebuilds() {
            outlaws.declare(MAYOR, ashford.id(), STRANGER).join();
            book.replaceAll(java.util.List.of());

            assertThat(outlaws.loadAll().join()).isEqualTo(1);
            assertThat(book.isOutlawed(ashford.id(), STRANGER)).isTrue();
        }

        @Test
        @DisplayName("and brings the officer and the date back with it")
        void loadRestoresProvenance() {
            // The round trip these two columns never had. They were written on every row from V14
            // and dropped on the way back: holds() was a SELECT 1 and all() selected two columns
            // into a two-field record, so "which of my officers did this" - the migration's stated
            // reason for storing them - could not be answered from a running server.
            outlaws.declare(MAYOR, ashford.id(), STRANGER).join();
            book.replaceAll(java.util.List.of());
            outlaws.loadAll().join();

            assertThat(outlaws.declarationsOf(ashford.id())).singleElement().satisfies(one -> {
                assertThat(one.who()).isEqualTo(STRANGER);
                assertThat(one.author()).contains(MAYOR);
                assertThat(one.declaredAt()).isEqualTo(NOW);
            });
        }

        @Test
        @DisplayName("a disbanded town's grudges go with it, in the table and in the cache")
        void disbandCascades() {
            // Both halves have to happen. The rows cascade in the database; the cache is told by
            // CivicCacheService, and without that a dissolved town would keep barring somebody
            // until the next restart - the exact gap diplomacy had.
            outlaws.declare(MAYOR, ashford.id(), STRANGER).join();

            towns.disband(MAYOR, ashford.id()).join();

            assertThat(store.inTransaction(t -> t.outlaws().all()).join()).isEmpty();
            assertThat(book.isOutlawed(ashford.id(), STRANGER)).isFalse();
            assertThat(book.size()).isZero();
        }
    }
}
