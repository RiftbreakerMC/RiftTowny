package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.ResidentNames;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.ResidentNameService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResidentNameServiceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId DRIFTER = ResidentId.of(UUID.randomUUID());
    private static final ResidentId UNKNOWN = ResidentId.of(UUID.randomUUID());

    private final ResidentNames names = ResidentNames.empty();

    private JdbcCivicStore store;
    private ResidentNameService service;
    private TownService towns;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        service = new ResidentNameService(store, CLOCK, names);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, TerritoryIndex.empty(),
                new CivicCacheService(store, CivicCache.empty(), warning -> { }));
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private Town riftholm() {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        return towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
    }

    @Test
    @DisplayName("a load names every town member")
    void loadNamesMembers() {
        riftholm();

        assertThat(service.loadAll().join()).isEqualTo(1);

        assertThat(names.of(MAYOR)).contains("Mayor");
    }

    @Test
    @DisplayName("somebody who belongs to no town is not held")
    void townlessResidentsAreNotCached() {
        riftholm();
        residents.save(Resident.newcomer(DRIFTER, "Drifter", NOW)).join();

        service.loadAll().join();

        assertThat(names.of(DRIFTER))
                .as("nothing names them, and holding them would grow with the account list")
                .isEmpty();
    }

    @Test
    @DisplayName("seeing a player records their name without waiting for a town")
    void seeingCachesImmediately() {
        residents.save(Resident.newcomer(DRIFTER, "Drifter", NOW)).join();

        service.seen(DRIFTER, "Drifter").join();

        assertThat(names.of(DRIFTER)).contains("Drifter");
    }

    @Test
    @DisplayName("a rename reaches both the cache and the stored name")
    void renamesArePersisted() {
        riftholm();
        service.loadAll().join();

        assertThat(service.seen(MAYOR, "Mayor2").join())
                .as("reported as a rename, so a caller can log it")
                .isTrue();

        assertThat(names.of(MAYOR)).contains("Mayor2");
        assertThat(store.inTransaction(t -> t.residents().find(MAYOR).orElseThrow().lastKnownName())
                .join())
                .as("otherwise last_known_name is a column that lies")
                .isEqualTo("Mayor2");
    }

    @Test
    @DisplayName("joining under the same name is not a rename")
    void sameNameIsNotARename() {
        riftholm();

        assertThat(service.seen(MAYOR, "Mayor").join()).isFalse();
        assertThat(names.of(MAYOR)).contains("Mayor");
    }

    @Test
    @DisplayName("a player RiftTowny has never seen is cached but not created")
    void unknownPlayersAreNotCreated() {
        assertThat(service.seen(UNKNOWN, "Newcomer").join()).isFalse();

        assertThat(names.of(UNKNOWN))
                .as("they can still be named while they are online")
                .contains("Newcomer");
        assertThat(store.inTransaction(t -> t.residents().find(UNKNOWN)).join())
                .as("a resident row is what membership hangs on; minting one per login fills the "
                        + "table with people who never join anything")
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown resident reads as somebody rather than as hex")
    void unknownNamesDegrade() {
        assertThat(names.describe(UNKNOWN)).isEqualTo("someone");
        assertThat(names.describe(null)).isEqualTo("someone");
    }

    @Test
    @DisplayName("a reload drops names that are no longer members")
    void reloadDropsFormerMembers() {
        final Town town = riftholm();
        residents.save(Resident.newcomer(DRIFTER, "Drifter", NOW)).join();
        towns.join(MAYOR, DRIFTER, town.id()).join();
        service.loadAll().join();
        assertThat(names.of(DRIFTER)).contains("Drifter");

        towns.leave(DRIFTER, town.id()).join();
        service.loadAll().join();

        assertThat(names.of(DRIFTER)).isEmpty();
        assertThat(names.size()).isEqualTo(1);
    }
}
