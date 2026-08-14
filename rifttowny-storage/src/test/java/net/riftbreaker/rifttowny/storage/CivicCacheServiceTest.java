package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.TownRoleService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache against a real database, because what is being asserted is that a committed change is
 * visible in memory afterwards — a property of the wiring between the services, the transaction and
 * the cache, which a fake store would assert nothing about.
 */
class CivicCacheServiceTest extends SqliteFixture {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T09:00:00Z"), ZoneOffset.UTC);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());
    private static final ResidentId OUTSIDER = ResidentId.of(UUID.randomUUID());

    private final CivicCache cache = CivicCache.empty();
    private final TerritoryIndex index = TerritoryIndex.empty();
    private final List<String> warnings = new ArrayList<>();

    private JdbcCivicStore store;
    private CivicCacheService civic;
    private TownService towns;
    private TownRoleService roles;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        civic = new CivicCacheService(store, cache, warnings::add);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, index, civic);
        roles = new TownRoleService(store, CLOCK, Set.of(), civic);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private Town foundRiftholm() {
        return towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
    }

    private void seeAsNewcomer(final ResidentId who, final String name) {
        residents.save(Resident.newcomer(who, name, CLOCK.instant())).join();
    }

    @Nested
    @DisplayName("loading")
    class Loading {

        @Test
        @DisplayName("a load fills the cache from storage")
        void loadFillsTheCache() {
            final Town riftholm = foundRiftholm();
            cache.forget(riftholm.id());
            assertThat(cache.knows(riftholm.id())).isFalse();

            final CivicCacheService.CivicLoad summary = civic.loadAll().join();

            assertThat(summary.towns()).isEqualTo(1);
            assertThat(summary.unreadable()).isEmpty();
            assertThat(summary.describe())
                    .isEqualTo("Loaded 1 town(s) and 0 nation(s) into memory.");
            assertThat(cache.knows(riftholm.id())).isTrue();
            assertThat(cache.townOf(MAYOR)).contains(riftholm.id());
        }

        @Test
        @DisplayName("a town with no role book is left out, named, and its land denies everything")
        void townWithoutRolesIsReported() {
            final Town riftholm = foundRiftholm();
            store.inTransaction(transaction -> {
                transaction.roles().delete(OrganisationScope.TOWN, riftholm.id().value());
                return null;
            }).join();

            final CivicCacheService.CivicLoad summary = civic.loadAll().join();

            assertThat(summary.towns()).isZero();
            assertThat(summary.unreadable()).containsExactly("Riftholm");
            assertThat(cache.knows(riftholm.id()))
                    .as("an uncacheable town must read as unknown, which denies rather than allows")
                    .isFalse();
            assertThat(warnings).anyMatch(warning -> warning.contains("Riftholm"));
        }

        @Test
        @DisplayName("a reload drops a town that has since been deleted")
        void reloadDropsDeletedTowns() {
            final Town riftholm = foundRiftholm();
            store.inTransaction(transaction -> {
                transaction.roles().delete(OrganisationScope.TOWN, riftholm.id().value());
                transaction.towns().delete(riftholm.id());
                return null;
            }).join();

            civic.loadAll().join();

            assertThat(cache.knows(riftholm.id())).isFalse();
            assertThat(cache.cachedResidents()).isZero();
        }
    }

    @Nested
    @DisplayName("following changes")
    class FollowingChanges {

        @Test
        @DisplayName("founding a town puts it in the cache without a reload")
        void foundingRefreshes() {
            final Town riftholm = foundRiftholm();

            assertThat(cache.knows(riftholm.id())).isTrue();
            assertThat(cache.town(riftholm.id()).map(TownFacts::displayName)).contains("Riftholm");
            assertThat(cache.townOf(MAYOR)).contains(riftholm.id());
        }

        @Test
        @DisplayName("a new resident is cached as a member immediately")
        void joiningRefreshes() {
            final Town riftholm = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");

            towns.join(MAYOR, CITIZEN, riftholm.id()).join();

            assertThat(cache.townOf(CITIZEN)).contains(riftholm.id());
            assertThat(cache.town(riftholm.id()).orElseThrow().hasResident(CITIZEN)).isTrue();
        }

        @Test
        @DisplayName("a kicked resident stops being cached as a member immediately")
        void kickingRefreshes() {
            final Town riftholm = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            towns.join(MAYOR, CITIZEN, riftholm.id()).join();

            towns.kick(MAYOR, CITIZEN, riftholm.id()).join();

            assertThat(cache.townOf(CITIZEN))
                    .as("a kicked player who stayed cached as a member would keep building there")
                    .isEmpty();
            assertThat(cache.town(riftholm.id()).orElseThrow().hasResident(CITIZEN)).isFalse();
        }

        @Test
        @DisplayName("disbanding removes the town and releases its residents from the cache")
        void disbandingRefreshes() {
            final Town riftholm = foundRiftholm();

            towns.disband(MAYOR, riftholm.id()).join();

            assertThat(cache.knows(riftholm.id())).isFalse();
            assertThat(cache.townOf(MAYOR)).isEmpty();
        }

        @Test
        @DisplayName("a renamed town is cached under its new name")
        void renamingRefreshes() {
            final Town riftholm = foundRiftholm();

            assertThat(towns.rename(MAYOR, riftholm.id(), "Highholm").join().succeeded()).isTrue();

            assertThat(cache.town(riftholm.id()).map(TownFacts::displayName)).contains("Highholm");
        }

        @Test
        @DisplayName("a leadership transfer moves who the cache treats as leader")
        void transferRefreshes() {
            final Town riftholm = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            towns.join(MAYOR, CITIZEN, riftholm.id()).join();

            towns.transferMayoralty(MAYOR, riftholm.id(), CITIZEN).join();

            final TownFacts facts = cache.town(riftholm.id()).orElseThrow();
            assertThat(facts.standingOf(CITIZEN)).isEqualTo(SystemRole.LEADER);
            assertThat(facts.standingOf(MAYOR)).isEqualTo(SystemRole.MEMBER);
        }

        @Test
        @DisplayName("revoking a permission from the member role reaches the cache")
        void roleEditsRefresh() {
            final Town riftholm = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            towns.join(MAYOR, CITIZEN, riftholm.id()).join();
            final RoleId member = memberRoleOf(riftholm.id());

            assertThat(cache.town(riftholm.id()).orElseThrow().allows(CITIZEN, Permission.BREAK))
                    .isTrue();
            roles.revoke(MAYOR, riftholm.id(), member, Permission.BREAK).join();

            assertThat(cache.town(riftholm.id()).orElseThrow().allows(CITIZEN, Permission.BREAK))
                    .as("a role edit that never reached the cache would keep granting what it took")
                    .isFalse();
        }

        @Test
        @DisplayName("a refused change leaves the cache alone")
        void refusedChangesDoNotTouchTheCache() {
            final Town riftholm = foundRiftholm();
            final long before = cache.generation();

            // OUTSIDER is nobody here, so this is refused before anything is written.
            assertThat(towns.rename(OUTSIDER, riftholm.id(), "Stolen").join().succeeded()).isFalse();

            assertThat(cache.generation()).isEqualTo(before);
            assertThat(cache.town(riftholm.id()).map(TownFacts::displayName)).contains("Riftholm");
        }

        @Test
        @DisplayName("trusting an outsider reaches the cache")
        void trustRefreshes() {
            final Town riftholm = foundRiftholm();
            // No service method grants trust yet, so it is written directly. The point being made is
            // about refresh(), not about a command that does not exist.
            store.inTransaction(transaction -> {
                transaction.towns().save(
                        transaction.towns().find(riftholm.id()).orElseThrow()
                                .trust(OUTSIDER).orElseThrow());
                return null;
            }).join();

            civic.refresh(riftholm.id()).join();

            assertThat(cache.isTrusted(riftholm.id(), OUTSIDER)).isTrue();
        }
    }

    @Nested
    @DisplayName("repair cases")
    class Repair {

        @Test
        @DisplayName("refreshing a town that no longer exists drops it rather than failing")
        void refreshingAVanishedTownDropsIt() {
            final Town riftholm = foundRiftholm();
            store.inTransaction(transaction -> {
                transaction.towns().delete(riftholm.id());
                return null;
            }).join();

            civic.refresh(riftholm.id()).join();

            assertThat(cache.knows(riftholm.id())).isFalse();
        }

        @Test
        @DisplayName("a town whose role book vanished is dropped and reported")
        void refreshingATownWithoutRolesDropsIt() {
            final Town riftholm = foundRiftholm();
            store.inTransaction(transaction -> {
                transaction.roles().delete(OrganisationScope.TOWN, riftholm.id().value());
                return null;
            }).join();

            civic.refresh(riftholm.id()).join();

            assertThat(cache.knows(riftholm.id())).isFalse();
            assertThat(warnings).anyMatch(warning -> warning.contains("Riftholm"));
        }

        @Test
        @DisplayName("refreshing nothing is not an error")
        void refreshingNullIsFine() {
            assertThat(civic.refresh(null).join()).isNull();
            assertThat(civic.refresh(TownId.random()).join()).isNull();
        }
    }

    private RoleId memberRoleOf(final TownId town) {
        return store.inTransaction(transaction -> {
            final RoleBook book = transaction.roles()
                    .find(OrganisationScope.TOWN, town.value()).orElseThrow();
            return book.systemRole(SystemRole.MEMBER).orElseThrow().id();
        }).join();
    }
}
