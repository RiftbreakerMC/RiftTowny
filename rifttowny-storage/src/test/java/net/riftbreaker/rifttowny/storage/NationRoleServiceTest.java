package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.NationRoleService;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nation roles, which share their rules with a town's and differ only in who counts as a member.
 *
 * <p>The escalation guards are asserted here as well as in {@code TownRoleServiceTest} on purpose.
 * They are shared code now, and a shared guard that is only exercised through one scope is a guard
 * that can silently stop applying to the other.
 */
class NationRoleServiceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final ResidentId KING = ResidentId.of(UUID.randomUUID());
    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());
    private static final ResidentId OUTSIDER = ResidentId.of(UUID.randomUUID());

    private JdbcCivicStore store;
    private NationRoleService roles;
    private NationService nations;
    private TownService towns;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        final CivicCacheService civic =
                new CivicCacheService(store, CivicCache.empty(), warning -> { });
        towns = new TownService(
                store, NamePolicy.defaults(), CLOCK, TerritoryIndex.empty(), civic);
        nations = new NationService(store, NamePolicy.defaults(), CLOCK, civic);
        roles = new NationRoleService(store, CLOCK, Set.of());
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private Town town(final ResidentId mayor, final String name) {
        residents.save(Resident.newcomer(mayor, name + "Mayor", NOW)).join();
        return towns.found(mayor, name + "Mayor", name).join().value().orElseThrow();
    }

    /** Valen on Riftholm, with Ashford admitted and its mayor a citizen. */
    private Nation valen() {
        final Town riftholm = town(KING, "Riftholm");
        final Nation nation =
                nations.found(KING, riftholm.id(), "Valen").join().value().orElseThrow();
        final TownId ashford = town(MAYOR, "Ashford").id();
        nations.invite(KING, nation.id(), ashford).join();
        nations.accept(MAYOR, ashford, nation.id()).join();
        return nation;
    }

    private RoleId envoy(final Nation nation, final Permission... permissions) {
        return roles.create(KING, nation.id(), "Envoy", 500, Set.of(permissions))
                .join().value().orElseThrow().id();
    }

    @Nested
    @DisplayName("editing")
    class Editing {

        @Test
        @DisplayName("the leader creates a role and it is stored against the nation")
        void creating() {
            final Nation nation = valen();

            final Role role = roles.create(KING, nation.id(), "Envoy", 500,
                    Set.of(Permission.MANAGE_ALLEGIANCE)).join().value().orElseThrow();

            assertThat(role.scope()).isEqualTo(OrganisationScope.NATION);
            assertThat(role.organisationId()).isEqualTo(nation.id().value());
            assertThat(roles.list(nation.id()).join()).extracting(Role::name).contains("Envoy");
        }

        @Test
        @DisplayName("a role is renamed, reprioritised and deleted")
        void editing() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation);

            assertThat(roles.rename(KING, nation.id(), envoy, "Ambassador").join().succeeded())
                    .isTrue();
            assertThat(roles.reprioritise(KING, nation.id(), envoy, 400).join().succeeded()).isTrue();
            assertThat(roles.delete(KING, nation.id(), envoy).join().succeeded()).isTrue();
            assertThat(roles.list(nation.id()).join()).extracting(Role::name)
                    .doesNotContain("Ambassador");
        }

        @Test
        @DisplayName("a citizen without MANAGE_ROLES cannot create one")
        void creatingNeedsPermission() {
            final Nation nation = valen();

            assertThat(roles.create(MAYOR, nation.id(), "Envoy", 500, Set.of()).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("someone in no member town is a visitor and cannot touch the roles")
        void outsidersAreVisitors() {
            final Nation nation = valen();
            residents.save(Resident.newcomer(OUTSIDER, "Outsider", NOW)).join();

            assertThat(roles.create(OUTSIDER, nation.id(), "Envoy", 500, Set.of()).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }
    }

    @Nested
    @DisplayName("citizenship")
    class Citizenship {

        @Test
        @DisplayName("a role goes to a citizen of a member town")
        void assigningToACitizen() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation);

            assertThat(roles.assign(KING, nation.id(), MAYOR, envoy).join().succeeded()).isTrue();
            assertThat(roles.permissionsOf(nation.id(), MAYOR).join())
                    .containsAll(SystemRole.MEMBER.defaultPermissions());
        }

        @Test
        @DisplayName("a role does not go to someone outside the nation")
        void assigningToAnOutsider() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation);
            residents.save(Resident.newcomer(OUTSIDER, "Outsider", NOW)).join();

            assertThat(roles.assign(KING, nation.id(), OUTSIDER, envoy).join().denial())
                    .as("a nation role held by a stranger is authority over towns they are not in")
                    .contains(ChangeDenial.NOT_A_CITIZEN_OF_THIS_NATION);
        }

        @Test
        @DisplayName("a resident of a town that is not a member is not a citizen")
        void residentsOfNonMemberTowns() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation);
            town(CITIZEN, "Highholm");

            assertThat(roles.assign(KING, nation.id(), CITIZEN, envoy).join().denial())
                    .contains(ChangeDenial.NOT_A_CITIZEN_OF_THIS_NATION);
        }

        @Test
        @DisplayName("a granted role is taken back again")
        void unassigning() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ALLEGIANCE);
            roles.assign(KING, nation.id(), MAYOR, envoy).join();

            assertThat(roles.unassign(KING, nation.id(), MAYOR, envoy).join().succeeded()).isTrue();
            assertThat(roles.permissionsOf(nation.id(), MAYOR).join())
                    .doesNotContain(Permission.MANAGE_ALLEGIANCE);
        }
    }

    @Nested
    @DisplayName("the escalation guards apply here too")
    class Escalation {

        @Test
        @DisplayName("an officer cannot create a role at or above their own rank")
        void cannotCreateAboveSelf() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ROLES);
            roles.assign(KING, nation.id(), MAYOR, envoy).join();

            assertThat(roles.create(MAYOR, nation.id(), "Regent", 500, Set.of()).join().denial())
                    .contains(ChangeDenial.CANNOT_CREATE_ROLE_ABOVE_SELF);
            assertThat(roles.create(MAYOR, nation.id(), "Clerk", 200, Set.of()).join().succeeded())
                    .isTrue();
        }

        @Test
        @DisplayName("an officer cannot write a permission they do not hold into a role")
        void cannotGrantWhatTheyLack() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ROLES);
            roles.assign(KING, nation.id(), MAYOR, envoy).join();
            final RoleId clerk =
                    roles.create(MAYOR, nation.id(), "Clerk", 200, Set.of()).join()
                            .value().orElseThrow().id();

            assertThat(roles.grant(MAYOR, nation.id(), clerk, Permission.DISBAND).join().denial())
                    .contains(ChangeDenial.CANNOT_GRANT_UNHELD_PERMISSION);
        }

        @Test
        @DisplayName("an officer cannot edit a role they do not outrank")
        void cannotEditTheirEquals() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ROLES);
            roles.assign(KING, nation.id(), MAYOR, envoy).join();

            assertThat(roles.rename(MAYOR, nation.id(), envoy, "Grand Envoy").join().denial())
                    .as("two officers of equal rank could otherwise demote each other in a loop")
                    .contains(ChangeDenial.INSUFFICIENT_ROLE_PRIORITY);
        }

        @Test
        @DisplayName("handing out a role is bounded by what the giver holds")
        void cannotAssignWhatTheyLack() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.ASSIGN_ROLES, Permission.MANAGE_ROLES);
            roles.assign(KING, nation.id(), MAYOR, envoy).join();
            // A role loaded with DISBAND by the leader, ranked below the envoy.
            final RoleId legacy = roles.create(KING, nation.id(), "Marshal", 200,
                    Set.of(Permission.DISBAND)).join().value().orElseThrow().id();

            assertThat(roles.assign(MAYOR, nation.id(), MAYOR, legacy).join().denial())
                    .as("otherwise ASSIGN_ROLES alone reaches every permission a predecessor wrote")
                    .contains(ChangeDenial.CANNOT_GRANT_UNHELD_PERMISSION);
        }

        @Test
        @DisplayName("the leader is bounded by none of it")
        void theLeaderIsUnbounded() {
            final Nation nation = valen();

            assertThat(roles.create(KING, nation.id(), "Marshal", 999,
                    Set.of(Permission.DISBAND)).join().succeeded()).isTrue();
        }
    }

    @Test
    @DisplayName("a nation's roles and a town's do not see each other")
    void scopesAreSeparate() {
        final Nation nation = valen();
        envoy(nation);

        final RoleBook townBook = store.inTransaction(transaction -> transaction.roles()
                .find(OrganisationScope.TOWN, nation.capital().value()).orElseThrow()).join();

        assertThat(townBook.findByName("Envoy"))
                .as("a nation role in a town's book would be authority nobody granted")
                .isEmpty();
    }
}
