package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.NationCache;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationProfile;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.NationService;
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

/**
 * The nation cache, and whether it keeps up.
 *
 * <p>A cache is only worth having if nothing can change the thing it copies without it hearing. The
 * nation is changed by nine service methods and the refresh is driven off what each returns rather
 * than from a nation id threaded through nine call sites — so what is actually tested here is that
 * every one of those nine arrives, including a disband, which is the one that must.</p>
 */
class NationCacheTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final ResidentId KING = ResidentId.of(UUID.randomUUID());
    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());

    private final CivicCache civicCache = CivicCache.empty();
    private final NationCache nationCache = NationCache.empty();

    private JdbcCivicStore store;
    private CivicCacheService civic;
    private TownService towns;
    private NationService nations;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        civic = new CivicCacheService(store, civicCache, nationCache, warning -> { });
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, TerritoryIndex.empty(), civic);
        nations = new NationService(store, NamePolicy.defaults(), CLOCK, civic);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private Nation valen() {
        residents.save(Resident.newcomer(KING, "King", NOW)).join();
        final Town capital = towns.found(KING, "King", "Riftholm").join().value().orElseThrow();
        return nations.found(KING, capital.id(), "Valen").join().value().orElseThrow();
    }

    private Town ashford() {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        return towns.found(MAYOR, "Mayor", "Ashford").join().value().orElseThrow();
    }

    @Test
    @DisplayName("founding a nation puts it in the cache")
    void foundingCaches() {
        final Nation valen = valen();

        assertThat(nationCache.knows(valen.id())).isTrue();
        assertThat(nationCache.nameOf(valen.id())).contains("Valen");
    }

    @Test
    @DisplayName("a startup load fills it from storage")
    void loadFills() {
        final Nation valen = valen();
        nationCache.forget(valen.id());

        final var summary = civic.loadAll().join();

        assertThat(summary.nations()).isEqualTo(1);
        assertThat(nationCache.nameOf(valen.id())).contains("Valen");
    }

    @Test
    @DisplayName("a rename reaches the cache, or every placeholder shows the old name")
    void renameReachesTheCache() {
        final Nation valen = valen();

        nations.rename(KING, valen.id(), "Highmarch").join();

        assertThat(nationCache.nameOf(valen.id())).contains("Highmarch");
    }

    @Test
    @DisplayName("a profile change reaches the cache")
    void profileReachesTheCache() {
        final Nation valen = valen();

        nations.setProfile(KING, valen.id(), profile -> profile.withTag("VAL")).join();

        assertThat(nationCache.nation(valen.id()))
                .map(Nation::profile)
                .map(NationProfile::tag)
                .contains("VAL");
    }

    @Test
    @DisplayName("a town joining is reflected in the nation's member list")
    void joiningReachesTheCache() {
        final Nation valen = valen();
        final Town ashford = ashford();
        nations.invite(KING, valen.id(), ashford.id()).join();
        nations.accept(MAYOR, ashford.id(), valen.id()).join();

        assertThat(nationCache.nation(valen.id()))
                .map(Nation::townCount)
                .contains(2);
        assertThat(nationCache.of(ashford.id())).map(Nation::id).contains(valen.id());
    }

    @Test
    @DisplayName("a town leaving is reflected too")
    void leavingReachesTheCache() {
        final Nation valen = valen();
        final Town ashford = ashford();
        nations.invite(KING, valen.id(), ashford.id()).join();
        nations.accept(MAYOR, ashford.id(), valen.id()).join();

        nations.leave(MAYOR, ashford.id()).join();

        assertThat(nationCache.nation(valen.id())).map(Nation::townCount).contains(1);
        assertThat(nationCache.of(ashford.id())).isEmpty();
    }

    @Test
    @DisplayName("a crown handed over reaches the cache")
    void crownReachesTheCache() {
        final Nation valen = valen();
        final Town ashford = ashford();
        nations.invite(KING, valen.id(), ashford.id()).join();
        nations.accept(MAYOR, ashford.id(), valen.id()).join();

        nations.transferLeadership(KING, valen.id(), MAYOR).join();

        assertThat(nationCache.nation(valen.id())).map(Nation::leader).contains(MAYOR);
    }

    @Test
    @DisplayName("a disbanded nation is forgotten, not left answering with a name nobody holds")
    void disbandForgets() {
        // The case the refresh is driven off the returned value to catch: a disband returns a
        // NationId rather than a Nation, and it is the one refresh that must not be missed.
        final Nation valen = valen();

        nations.disband(KING, valen.id()).join();

        assertThat(nationCache.knows(valen.id())).isFalse();
        assertThat(nationCache.nameOf(valen.id())).isEmpty();
        assertThat(nationCache.all()).isEmpty();
    }

    @Test
    @DisplayName("a dissolved nation stops claiming its former towns")
    void disbandClearsMembership() {
        final Nation valen = valen();
        final Town ashford = ashford();
        nations.invite(KING, valen.id(), ashford.id()).join();
        nations.accept(MAYOR, ashford.id(), valen.id()).join();

        nations.disband(KING, valen.id()).join();

        assertThat(nationCache.of(ashford.id())).isEmpty();
    }
}
