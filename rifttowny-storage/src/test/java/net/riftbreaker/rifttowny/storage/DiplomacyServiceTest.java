package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.NationCache;
import net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook;
import net.riftbreaker.rifttowny.domain.diplomacy.Relation;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.DiplomacyService;
import net.riftbreaker.rifttowny.domain.service.NationService;
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
 * Declaring allies and enemies, through real storage.
 *
 * <p>The domain test covers the rule. What only a database shows is that the two halves of an
 * alliance survive being written separately, that a dissolved nation's declarations really go, and
 * that the cache and the table agree afterwards.</p>
 */
class DiplomacyServiceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final ResidentId VALEN_KING = ResidentId.of(UUID.randomUUID());
    private static final ResidentId ASHMARK_KING = ResidentId.of(UUID.randomUUID());

    private final CivicCache civicCache = CivicCache.empty();
    private final NationCache nationCache = NationCache.empty();
    private final DiplomacyBook book = DiplomacyBook.empty();

    private JdbcCivicStore store;
    private DiplomacyService diplomacy;
    private NationService nations;
    private TownService towns;
    private JdbcResidentRepository residents;

    private Nation valen;
    private Nation ashmark;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        final CivicCacheService civic =
                new CivicCacheService(store, civicCache, nationCache, warning -> { });
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, TerritoryIndex.empty(), civic);
        nations = new NationService(store, NamePolicy.defaults(), CLOCK, civic);
        diplomacy = new DiplomacyService(store, CLOCK, book);
        residents = new JdbcResidentRepository(database, DIRECT);

        valen = foundNation(VALEN_KING, "Riftholm", "Valen");
        ashmark = foundNation(ASHMARK_KING, "Ashford", "Ashmark");
    }

    private Nation foundNation(final ResidentId king, final String townName, final String name) {
        residents.save(Resident.newcomer(king, name + "King", NOW)).join();
        final Town capital = towns.found(king, name + "King", townName).join().value().orElseThrow();
        return nations.found(king, capital.id(), name).join().value().orElseThrow();
    }

    @Nested
    @DisplayName("an alliance")
    class Alliances {

        @Test
        @DisplayName("one nation declaring it does not make one")
        void oneSideIsNotEnough() {
            assertThat(diplomacy.declare(VALEN_KING, valen.id(), Relation.ALLY, ashmark.id())
                    .join().succeeded()).isTrue();

            assertThat(book.areAllied(valen.id(), ashmark.id())).isFalse();
            assertThat(book.offeredAlliances(valen.id())).containsExactly(ashmark.id());
        }

        @Test
        @DisplayName("both declaring it does, and each wrote only their own row")
        void bothSidesMakeAnAlliance() {
            diplomacy.declare(VALEN_KING, valen.id(), Relation.ALLY, ashmark.id()).join();
            diplomacy.declare(ASHMARK_KING, ashmark.id(), Relation.ALLY, valen.id()).join();

            assertThat(book.areAllied(valen.id(), ashmark.id())).isTrue();
            assertThat(store.inTransaction(t -> t.relations().all()).join()).hasSize(2);
        }

        @Test
        @DisplayName("withdrawing one side ends it and leaves the other's offer standing")
        void withdrawingEndsIt() {
            diplomacy.declare(VALEN_KING, valen.id(), Relation.ALLY, ashmark.id()).join();
            diplomacy.declare(ASHMARK_KING, ashmark.id(), Relation.ALLY, valen.id()).join();

            diplomacy.withdraw(ASHMARK_KING, ashmark.id(), Relation.ALLY, valen.id()).join();

            assertThat(book.areAllied(valen.id(), ashmark.id())).isFalse();
            assertThat(book.offeredAlliances(valen.id())).containsExactly(ashmark.id());
            assertThat(store.inTransaction(t -> t.relations().all()).join()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("an enmity")
    class Enmities {

        @Test
        @DisplayName("takes one nation and binds only that one")
        void oneSided() {
            diplomacy.declare(VALEN_KING, valen.id(), Relation.ENEMY, ashmark.id()).join();

            assertThat(book.isEnemy(valen.id(), ashmark.id())).isTrue();
            assertThat(book.isEnemy(ashmark.id(), valen.id())).isFalse();
            assertThat(book.hostile(ashmark.id(), valen.id())).isTrue();
        }

        @Test
        @DisplayName("declaring an enemy withdraws the alliance offer, so both never stand")
        void theOppositeIsWithdrawn() {
            // Otherwise "are we allied" and "are we at war" are both true, and every reader has to
            // decide which wins.
            diplomacy.declare(VALEN_KING, valen.id(), Relation.ALLY, ashmark.id()).join();

            diplomacy.declare(VALEN_KING, valen.id(), Relation.ENEMY, ashmark.id()).join();

            assertThat(book.hasDeclared(valen.id(), Relation.ALLY, ashmark.id())).isFalse();
            assertThat(book.isEnemy(valen.id(), ashmark.id())).isTrue();
            assertThat(store.inTransaction(t -> t.relations().all()).join()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusals {

        @Test
        @DisplayName("somebody with no standing in the nation")
        void needsThePermission() {
            final var refused =
                    diplomacy.declare(ASHMARK_KING, valen.id(), Relation.ENEMY, ashmark.id()).join();

            assertThat(refused.succeeded()).isFalse();
            assertThat(refused.denial()).contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("declaring something already declared")
        void duplicatesAreRefused() {
            diplomacy.declare(VALEN_KING, valen.id(), Relation.ALLY, ashmark.id()).join();

            assertThat(diplomacy.declare(VALEN_KING, valen.id(), Relation.ALLY, ashmark.id())
                    .join().denial())
                    .contains(ChangeDenial.NOTHING_TO_CHANGE);
        }

        @Test
        @DisplayName("withdrawing something never declared")
        void withdrawingNothing() {
            assertThat(diplomacy.withdraw(VALEN_KING, valen.id(), Relation.ALLY, ashmark.id())
                    .join().denial())
                    .contains(ChangeDenial.NO_SUCH_DECLARATION);
        }

        @Test
        @DisplayName("a nation declaring about itself")
        void selfDeclarations() {
            assertThat(diplomacy.declare(VALEN_KING, valen.id(), Relation.ALLY, valen.id())
                    .join().denial())
                    .contains(ChangeDenial.CANNOT_DECLARE_ON_SELF);
        }
    }

    @Nested
    @DisplayName("afterwards")
    class Afterwards {

        @Test
        @DisplayName("a startup load rebuilds the cache from the table")
        void loadRebuilds() {
            diplomacy.declare(VALEN_KING, valen.id(), Relation.ALLY, ashmark.id()).join();
            diplomacy.declare(ASHMARK_KING, ashmark.id(), Relation.ALLY, valen.id()).join();
            book.replaceAll(java.util.List.of());

            assertThat(diplomacy.loadAll().join()).isEqualTo(2);
            assertThat(book.areAllied(valen.id(), ashmark.id())).isTrue();
        }

        @Test
        @DisplayName("a dissolved nation's declarations go with it, in both directions")
        void disbandCascades() {
            // The rows cascade in the database; the cache is told separately. Both have to happen,
            // or a dead nation stays somebody's ally.
            diplomacy.declare(VALEN_KING, valen.id(), Relation.ALLY, ashmark.id()).join();
            diplomacy.declare(ASHMARK_KING, ashmark.id(), Relation.ALLY, valen.id()).join();

            nations.disband(ASHMARK_KING, ashmark.id()).join();
            diplomacy.forget(ashmark.id());

            assertThat(store.inTransaction(t -> t.relations().all()).join()).isEmpty();
            assertThat(book.areAllied(valen.id(), ashmark.id())).isFalse();
            assertThat(book.size()).isZero();
        }

        @Test
        @DisplayName("a nation's own screen sees both what it declared and what was declared about it")
        void involvingSeesBothDirections() {
            diplomacy.declare(VALEN_KING, valen.id(), Relation.ENEMY, ashmark.id()).join();

            assertThat(diplomacy.involving(ashmark.id()).join())
                    .singleElement()
                    .extracting(DiplomacyBook.Declaration::declarer)
                    .isEqualTo(valen.id());
        }
    }
}
