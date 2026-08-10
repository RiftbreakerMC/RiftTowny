package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.NameProblem;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
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
import net.riftbreaker.rifttowny.domain.service.ServiceResult;
import net.riftbreaker.rifttowny.domain.service.TownService;
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
 * The service against a real database, because the guarantees being asserted — that a refused
 * founding leaves nothing behind, that an announcement and its state change commit together — are
 * properties of the transaction, and a mocked store would assert only that the code calls the
 * methods the test expects.
 */
class TownServiceTest extends SqliteFixture {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId OFFICER = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());

    private TownService service;
    private JdbcCivicStore store;
    private JdbcTownRepository towns;
    private JdbcResidentRepository residents;
    private JdbcOutboxRepository outbox;

    @BeforeEach
    void createService() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        service = new TownService(store, NamePolicy.defaults(), CLOCK);
        towns = new JdbcTownRepository(database, DIRECT);
        residents = new JdbcResidentRepository(database, DIRECT);
        outbox = new JdbcOutboxRepository(database, DIRECT);
    }

    private Town foundRiftholm() {
        return service.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
    }

    private void seeAsNewcomer(final ResidentId who, final String name) {
        residents.save(Resident.newcomer(who, name, CLOCK.instant())).join();
    }

    /** Adds a member and gives them a role carrying exactly the permissions named. */
    private RoleId giveRole(
            final TownId town, final ResidentId who, final int priority, final Permission... granted) {
        final RoleId roleId = RoleId.random();
        store.inTransaction(transaction -> {
            final RoleBook book = transaction.roles()
                    .find(OrganisationScope.TOWN, town.value()).orElseThrow();
            final Role role = Role.custom(
                    roleId, OrganisationScope.TOWN, town.value(), "Rank" + priority, priority,
                    Set.of(granted), CLOCK.instant());
            transaction.roles().save(book.create(role, Set.of()).orElseThrow()
                    .assign(who, roleId).orElseThrow());
            return null;
        }).join();
        return roleId;
    }

    @Nested
    @DisplayName("founding")
    class Founding {

        @Test
        @DisplayName("creates the town, the resident, the role book and the announcement together")
        void foundingCommitsEverything() {
            final Town town = foundRiftholm();

            assertThat(town.mayor()).isEqualTo(MAYOR);
            assertThat(towns.find(town.id()).join().orElseThrow().residents()).containsExactly(MAYOR);
            assertThat(residents.find(MAYOR).join().orElseThrow().town()).contains(town.id());
            assertThat(outbox.counts().join().pending()).isEqualTo(1L);

            final RoleBook book = store.inTransaction(transaction -> transaction.roles()
                    .find(OrganisationScope.TOWN, town.id().value()).orElseThrow()).join();
            assertThat(book.size())
                    .as("a town with no role book cannot answer a single permission question")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a bad name is rejected with every problem, and nothing is written")
        void badNameWritesNothing() {
            final ServiceResult<Town> result = service.found(MAYOR, "Mayor", "1 !").join();

            assertThat(result.nameProblems())
                    .contains(NameProblem.MUST_START_WITH_LETTER, NameProblem.CONTAINS_WHITESPACE);
            assertThat(towns.count().join()).isZero();
            assertThat(residents.find(MAYOR).join()).isEmpty();
            assertThat(outbox.counts().join().total()).isZero();
        }

        @Test
        @DisplayName("a taken name is refused, and leaves no role book or resident behind")
        void takenNameRollsBack() {
            foundRiftholm();

            final ServiceResult<Town> result = service.found(CITIZEN, "Citizen", "riftholm").join();

            assertThat(result.denial()).contains(ChangeDenial.NAME_TAKEN);
            assertThat(towns.count().join()).isEqualTo(1);
            assertThat(residents.find(CITIZEN).join()).isEmpty();
            assertThat(outbox.counts().join().total()).isEqualTo(1L);
        }

        @Test
        @DisplayName("someone already in a town cannot found another")
        void oneTownPerResidentIsEnforced() {
            foundRiftholm();

            assertThat(service.found(MAYOR, "Mayor", "Ashford").join().denial())
                    .contains(ChangeDenial.ALREADY_IN_ANOTHER_TOWN);
            assertThat(towns.count().join()).isEqualTo(1);
        }

        @Test
        @DisplayName("the founder is the leader and therefore holds every permission")
        void founderHoldsEverything() {
            final Town town = foundRiftholm();

            assertThat(service.permissionsOf(MAYOR, town.id()).join())
                    .containsExactlyInAnyOrder(Permission.values());
        }
    }

    @Nested
    @DisplayName("authority")
    class Authority {

        @Test
        @DisplayName("an ordinary member cannot add residents")
        void memberCannotInvite() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, CITIZEN, town.id()).join();
            seeAsNewcomer(OFFICER, "Officer");

            assertThat(service.join(CITIZEN, OFFICER, town.id()).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(towns.find(town.id()).join().orElseThrow().residents())
                    .doesNotContain(OFFICER);
        }

        @Test
        @DisplayName("a role carrying the permission is enough, without being mayor")
        void roleGrantsTheAuthority() {
            final Town town = foundRiftholm();
            seeAsNewcomer(OFFICER, "Officer");
            service.join(MAYOR, OFFICER, town.id()).join();
            giveRole(town.id(), OFFICER, 500, Permission.INVITE_RESIDENT);
            seeAsNewcomer(CITIZEN, "Citizen");

            assertThat(service.join(OFFICER, CITIZEN, town.id()).join().succeeded()).isTrue();
        }

        @Test
        @DisplayName("an outsider has no authority at all")
        void outsiderHasNoAuthority() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");

            assertThat(service.join(OFFICER, CITIZEN, town.id()).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("a member cannot rename or disband the town")
        void memberCannotRenameOrDisband() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, CITIZEN, town.id()).join();

            assertThat(service.rename(CITIZEN, town.id(), "Ashford").join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(service.disband(CITIZEN, town.id()).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(towns.find(town.id()).join().orElseThrow().name().display())
                    .isEqualTo("Riftholm");
        }

        @Test
        @DisplayName("a refused rename does not consume the name or queue an announcement")
        void refusedRenameWritesNothing() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, CITIZEN, town.id()).join();
            final long queued = outbox.counts().join().total();

            service.rename(CITIZEN, town.id(), "Ashford").join();

            assertThat(towns.findByName("ashford").join()).isEmpty();
            assertThat(outbox.counts().join().total()).isEqualTo(queued);
        }

        @Test
        @DisplayName("a town with no role book refuses rather than defaulting to allowed")
        void missingRoleBookRefuses() {
            final Town town = foundRiftholm();
            store.inTransaction(transaction -> {
                transaction.roles().delete(OrganisationScope.TOWN, town.id().value());
                return null;
            }).join();

            assertThat(service.rename(MAYOR, town.id(), "Ashford").join().denial())
                    .as("a missing book is a repair case, not permission to do anything")
                    .contains(ChangeDenial.ROLE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("leaving and removing")
    class Departure {

        @Test
        @DisplayName("a resident may always leave of their own accord")
        void leavingNeedsNoPermission() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, CITIZEN, town.id()).join();

            assertThat(service.leave(CITIZEN, town.id()).join().succeeded()).isTrue();
            assertThat(residents.find(CITIZEN).join().orElseThrow().town()).isEmpty();
        }

        @Test
        @DisplayName("the mayor cannot leave while others remain, and nothing changes")
        void mayorCannotLeave() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, CITIZEN, town.id()).join();

            assertThat(service.leave(MAYOR, town.id()).join().denial())
                    .contains(ChangeDenial.MAYOR_MUST_TRANSFER_FIRST);
            assertThat(residents.find(MAYOR).join().orElseThrow().town()).contains(town.id());
        }

        @Test
        @DisplayName("the last resident is told to disband instead of leaving")
        void lastResidentIsToldToDisband() {
            final Town town = foundRiftholm();

            assertThat(service.leave(MAYOR, town.id()).join().denial())
                    .contains(ChangeDenial.LAST_RESIDENT_MUST_DISBAND_INSTEAD);
        }

        @Test
        @DisplayName("kicking needs the permission")
        void kickNeedsPermission() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            seeAsNewcomer(OFFICER, "Officer");
            service.join(MAYOR, CITIZEN, town.id()).join();
            service.join(MAYOR, OFFICER, town.id()).join();

            assertThat(service.kick(CITIZEN, OFFICER, town.id()).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("an officer may kick someone below them")
        void officerKicksBelow() {
            final Town town = foundRiftholm();
            seeAsNewcomer(OFFICER, "Officer");
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, OFFICER, town.id()).join();
            service.join(MAYOR, CITIZEN, town.id()).join();
            giveRole(town.id(), OFFICER, 500, Permission.KICK_RESIDENT);

            assertThat(service.kick(OFFICER, CITIZEN, town.id()).join().succeeded()).isTrue();
            assertThat(residents.find(CITIZEN).join().orElseThrow().town()).isEmpty();
        }

        @Test
        @DisplayName("an officer may not kick an equal, or two equals could remove each other")
        void officerCannotKickAnEqual() {
            final Town town = foundRiftholm();
            seeAsNewcomer(OFFICER, "Officer");
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, OFFICER, town.id()).join();
            service.join(MAYOR, CITIZEN, town.id()).join();
            final RoleId rank = giveRole(town.id(), OFFICER, 500, Permission.KICK_RESIDENT);
            store.inTransaction(transaction -> {
                final RoleBook book = transaction.roles()
                        .find(OrganisationScope.TOWN, town.id().value()).orElseThrow();
                transaction.roles().save(book.assign(CITIZEN, rank).orElseThrow());
                return null;
            }).join();

            assertThat(service.kick(OFFICER, CITIZEN, town.id()).join().denial())
                    .contains(ChangeDenial.INSUFFICIENT_ROLE_PRIORITY);
            assertThat(residents.find(CITIZEN).join().orElseThrow().town()).contains(town.id());
        }

        @Test
        @DisplayName("nobody may kick the mayor, however senior their role")
        void nobodyKicksTheMayor() {
            final Town town = foundRiftholm();
            seeAsNewcomer(OFFICER, "Officer");
            service.join(MAYOR, OFFICER, town.id()).join();
            giveRole(town.id(), OFFICER, 999, Permission.KICK_RESIDENT);

            assertThat(service.kick(OFFICER, MAYOR, town.id()).join().denial())
                    .as("the highest configurable rank is still below the leader")
                    .contains(ChangeDenial.INSUFFICIENT_ROLE_PRIORITY);
        }
    }

    @Nested
    @DisplayName("leadership, renaming and disbanding")
    class Lifecycle {

        @Test
        @DisplayName("only the sitting mayor may hand over the mayoralty")
        void onlyMayorTransfers() {
            final Town town = foundRiftholm();
            seeAsNewcomer(OFFICER, "Officer");
            service.join(MAYOR, OFFICER, town.id()).join();
            giveRole(town.id(), OFFICER, 999, Permission.values());

            assertThat(service.transferMayoralty(OFFICER, town.id(), OFFICER).join().denial())
                    .as("a role that could hand over the mayoralty is a coup with extra steps")
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("the mayoralty transfers to a resident, keeping the civic account")
        void mayoraltyTransfers() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, CITIZEN, town.id()).join();

            assertThat(service.transferMayoralty(MAYOR, town.id(), CITIZEN).join().succeeded())
                    .isTrue();

            final Town loaded = towns.find(town.id()).join().orElseThrow();
            assertThat(loaded.mayor()).isEqualTo(CITIZEN);
            assertThat(loaded.bankAccountId()).isEqualTo(town.bankAccountId());
        }

        @Test
        @DisplayName("the new mayor gains every permission and the old one drops to member")
        void authorityFollowsTheMayoralty() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, CITIZEN, town.id()).join();
            service.transferMayoralty(MAYOR, town.id(), CITIZEN).join();

            assertThat(service.permissionsOf(CITIZEN, town.id()).join())
                    .containsExactlyInAnyOrder(Permission.values());
            assertThat(service.permissionsOf(MAYOR, town.id()).join())
                    .doesNotContain(Permission.DISBAND);
        }

        @Test
        @DisplayName("renaming keeps the id and the civic account")
        void renameKeepsIdentity() {
            final Town town = foundRiftholm();

            assertThat(service.rename(MAYOR, town.id(), "Ashford").join().succeeded()).isTrue();

            final Town loaded = towns.find(town.id()).join().orElseThrow();
            assertThat(loaded.name().display()).isEqualTo("Ashford");
            assertThat(loaded.bankAccountId()).isEqualTo(town.bankAccountId());
        }

        @Test
        @DisplayName("a town may recapitalise its own name without colliding with itself")
        void recapitalisingOwnNameIsAllowed() {
            final Town town = foundRiftholm();

            assertThat(service.rename(MAYOR, town.id(), "RIFTHOLM").join().succeeded()).isTrue();
        }

        @Test
        @DisplayName("renaming onto another town's name is refused")
        void renameOntoAnotherTownIsRefused() {
            final Town riftholm = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            service.found(CITIZEN, "Citizen", "Ashford").join();

            assertThat(service.rename(MAYOR, riftholm.id(), "Ashford").join().denial())
                    .contains(ChangeDenial.NAME_TAKEN);
            assertThat(towns.find(riftholm.id()).join().orElseThrow().name().display())
                    .isEqualTo("Riftholm");
        }

        @Test
        @DisplayName("disbanding removes the town, its role book, and releases its residents")
        void disbandCleansUp() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, CITIZEN, town.id()).join();

            assertThat(service.disband(MAYOR, town.id()).join().succeeded()).isTrue();

            assertThat(towns.find(town.id()).join()).isEmpty();
            assertThat(residents.find(MAYOR).join().orElseThrow().town()).isEmpty();
            assertThat(residents.find(CITIZEN).join().orElseThrow().town()).isEmpty();
            assertThat(store.inTransaction(transaction -> transaction.roles()
                    .find(OrganisationScope.TOWN, town.id().value())).join())
                    .as("an orphaned role book would be inherited by a town reusing the id")
                    .isEmpty();
        }

        @Test
        @DisplayName("disbanding a town that does not exist is refused")
        void disbandUnknownTown() {
            assertThat(service.disband(MAYOR, TownId.random()).join().denial())
                    .contains(ChangeDenial.TOWN_NOT_FOUND);
        }

        @Test
        @DisplayName("the standing of a leader, a member and an outsider are all distinguished")
        void standingIsResolvedCorrectly() {
            final Town town = foundRiftholm();
            seeAsNewcomer(CITIZEN, "Citizen");
            service.join(MAYOR, CITIZEN, town.id()).join();
            final Town loaded = towns.find(town.id()).join().orElseThrow();

            assertThat(loaded.standingOf(MAYOR)).isEqualTo(SystemRole.LEADER);
            assertThat(loaded.standingOf(CITIZEN)).isEqualTo(SystemRole.MEMBER);
            assertThat(loaded.standingOf(OFFICER)).isEqualTo(SystemRole.VISITOR);
        }
    }
}
