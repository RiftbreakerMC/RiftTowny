package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.SpawnService;
import net.riftbreaker.rifttowny.domain.service.TerritoryService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.SpawnPoint;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpawnServiceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkKey HOME = new ChunkKey(WORLD, 0, 0);
    private static final ChunkKey MARKET = new ChunkKey(WORLD, 1, 0);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());
    private static final ResidentId STRANGER = ResidentId.of(UUID.randomUUID());

    /** Inside HOME: chunk 0,0 covers blocks 0-15. */
    private static final SpawnPoint IN_HOME = new SpawnPoint(WORLD, 8.5, 64.0, 8.5, 90f, 0f);
    /** Inside MARKET: chunk 1,0 covers blocks 16-31. */
    private static final SpawnPoint IN_MARKET = new SpawnPoint(WORLD, 20.5, 64.0, 8.5, 0f, 0f);
    private static final SpawnPoint IN_WILDERNESS = new SpawnPoint(WORLD, 800.5, 64.0, 800.5, 0f, 0f);

    private final TerritoryIndex index = TerritoryIndex.empty();
    private final CivicCache civicCache = CivicCache.empty();

    private JdbcCivicStore store;
    private SpawnService spawns;
    private TownService towns;
    private TerritoryService territory;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, index,
                new CivicCacheService(store, civicCache, warning -> { }));
        territory = new TerritoryService(store, CLOCK, index);
        spawns = new SpawnService(store, CLOCK, index);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private Town riftholm() {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        final Town town = towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
        territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join();
        territory.claim(MAYOR, town.id(), MARKET, ClaimKind.ORDINARY).join();
        return town;
    }

    private ResidentId citizenOf(final Town town) {
        residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
        towns.join(MAYOR, CITIZEN, town.id()).join();
        return CITIZEN;
    }

    @Nested
    @DisplayName("setting one")
    class Setting {

        @Test
        @DisplayName("a spawn is set where the actor stands, facing included")
        void setting() {
            final Town town = riftholm();

            final SpawnPoint set =
                    spawns.set(MAYOR, town.id(), IN_HOME).join().value().orElseThrow();

            assertThat(set).isEqualTo(IN_HOME);
            assertThat(spawns.of(town.id())).contains(IN_HOME);
            assertThat(spawns.of(town.id()).orElseThrow().yaw())
                    .as("a spawn that drops everybody looking at a wall is a coordinate")
                    .isEqualTo(90f);
        }

        @Test
        @DisplayName("a spawn cannot be set outside the town's own land")
        void outsideTheTown() {
            final Town town = riftholm();

            assertThat(spawns.set(MAYOR, town.id(), IN_WILDERNESS).join().denial())
                    .contains(ChangeDenial.CHUNK_NOT_CLAIMED);
            assertThat(spawns.of(town.id())).isEmpty();
        }

        @Test
        @DisplayName("a spawn cannot be set in another town's land")
        void insideAnotherTown() {
            final Town riftholm = riftholm();
            residents.save(Resident.newcomer(STRANGER, "Stranger", NOW)).join();
            final Town ashford =
                    towns.found(STRANGER, "Stranger", "Ashford").join().value().orElseThrow();

            assertThat(spawns.set(STRANGER, ashford.id(), IN_HOME).join().denial())
                    .as("otherwise a mayor hands every resident a teleport into a rival's vault")
                    .contains(ChangeDenial.CHUNK_OWNED_BY_ANOTHER_TOWN);
            assertThat(riftholm.id()).isNotEqualTo(ashford.id());
        }

        @Test
        @DisplayName("setting needs SET_SPAWN")
        void settingNeedsPermission() {
            final Town town = riftholm();
            citizenOf(town);

            assertThat(spawns.set(CITIZEN, town.id(), IN_HOME).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("setting it again moves it rather than adding a second")
        void movingIt() {
            final Town town = riftholm();
            spawns.set(MAYOR, town.id(), IN_HOME).join();

            spawns.set(MAYOR, town.id(), IN_MARKET).join();

            assertThat(spawns.of(town.id())).contains(IN_MARKET);
            assertThat(store.inTransaction(t -> t.spawns().all()).join()).hasSize(1);
        }

        @Test
        @DisplayName("clearing removes it, and clearing nothing says so")
        void clearing() {
            final Town town = riftholm();
            spawns.set(MAYOR, town.id(), IN_HOME).join();

            assertThat(spawns.clear(MAYOR, town.id()).join().succeeded()).isTrue();

            assertThat(spawns.of(town.id())).isEmpty();
            assertThat(spawns.clear(MAYOR, town.id()).join().denial())
                    .contains(ChangeDenial.NO_TOWN_SPAWN);
        }
    }

    @Nested
    @DisplayName("travelling to one")
    class Travelling {

        @Test
        @DisplayName("a resident is given the destination")
        void travelling() {
            final Town town = riftholm();
            spawns.set(MAYOR, town.id(), IN_HOME).join();
            citizenOf(town);

            assertThat(spawns.travelTo(CITIZEN, town.id()).join().value()).contains(IN_HOME);
        }

        @Test
        @DisplayName("a town with no spawn says so rather than failing obscurely")
        void noSpawn() {
            final Town town = riftholm();

            assertThat(spawns.travelTo(MAYOR, town.id()).join().denial())
                    .contains(ChangeDenial.NO_TOWN_SPAWN);
        }

        @Test
        @DisplayName("a role without TOWN_SPAWN cannot travel in")
        void travellingNeedsPermission() {
            final Town town = riftholm();
            spawns.set(MAYOR, town.id(), IN_HOME).join();
            citizenOf(town);
            revokeMemberPermission(town, Permission.TOWN_SPAWN);

            assertThat(spawns.travelTo(CITIZEN, town.id()).join().denial())
                    .as("a probationary role that cannot teleport in is a thing towns want")
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("a spawn whose land the town released is refused, and forgotten")
        void spawnOnReleasedLand() {
            final Town town = riftholm();
            spawns.set(MAYOR, town.id(), IN_MARKET).join();

            territory.unclaim(MAYOR, town.id(), MARKET).join();

            assertThat(spawns.travelTo(MAYOR, town.id()).join().denial())
                    .as("a stale spawn drops the player in somebody else's territory")
                    .contains(ChangeDenial.NO_TOWN_SPAWN);
            assertThat(spawns.of(town.id())).isEmpty();
            assertThat(store.inTransaction(t -> t.spawns().of(town.id())).join()).isEmpty();
        }

        @Test
        @DisplayName("unclaiming elsewhere leaves the spawn alone")
        void unrelatedUnclaimKeepsIt() {
            final Town town = riftholm();
            spawns.set(MAYOR, town.id(), IN_HOME).join();

            territory.unclaim(MAYOR, town.id(), MARKET).join();

            assertThat(spawns.clearIfOutsideTerritory(town.id()).join()).isFalse();
            assertThat(spawns.of(town.id())).contains(IN_HOME);
        }
    }

    @Test
    @DisplayName("spawns survive a restart")
    void spawnsReload() {
        final Town town = riftholm();
        spawns.set(MAYOR, town.id(), IN_HOME).join();

        final SpawnService reloaded = new SpawnService(store, CLOCK, index);
        assertThat(reloaded.loadAll().join()).isEqualTo(1);

        assertThat(reloaded.of(town.id())).contains(IN_HOME);
    }

    @Test
    @DisplayName("a disbanded town's spawn goes with it")
    void disbandRemovesIt() {
        final Town town = riftholm();
        spawns.set(MAYOR, town.id(), IN_HOME).join();

        towns.disband(MAYOR, town.id()).join();

        assertThat(store.inTransaction(t -> t.spawns().of(town.id())).join())
                .as("the row cascades from rt_town")
                .isEmpty();
    }

    @Test
    @DisplayName("the chunk a spawn sits in is worked out the same way everywhere")
    void chunkArithmetic() {
        assertThat(new SpawnPoint(WORLD, 8.5, 64, 8.5, 0f, 0f).chunk())
                .isEqualTo(new ChunkKey(WORLD, 0, 0));
        assertThat(new SpawnPoint(WORLD, -0.5, 64, -0.5, 0f, 0f).chunk())
                .as("just west of the origin is chunk -1, not chunk 0")
                .isEqualTo(new ChunkKey(WORLD, -1, -1));
        assertThat(new SpawnPoint(WORLD, -16.5, 64, -16.5, 0f, 0f).chunk())
                .isEqualTo(new ChunkKey(WORLD, -2, -2));
    }

    private void revokeMemberPermission(final Town town, final Permission permission) {
        store.inTransaction(transaction -> {
            final RoleBook book = transaction.roles()
                    .find(OrganisationScope.TOWN, town.id().value()).orElseThrow();
            final RoleId member = book.systemRole(SystemRole.MEMBER).orElseThrow().id();
            transaction.roles().save(book.revoke(member, permission).orElseThrow());
            return null;
        }).join();
        assertThat(Set.of(permission)).isNotEmpty();
    }
}
