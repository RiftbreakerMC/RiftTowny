package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.service.ServiceResult;
import net.riftbreaker.rifttowny.domain.service.TownRoleService;
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

class TownRoleServiceTest extends SqliteFixture {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId OFFICER = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());

    private JdbcCivicStore store;
    private TownService towns;
    private TownRoleService roles;
    private Town riftholm;

    @BeforeEach
    void setUp() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK);
        roles = new TownRoleService(store, CLOCK, Set.of());
        riftholm = towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
    }

    private void addMember(final ResidentId who, final String name) {
        new JdbcResidentRepository(database, DIRECT)
                .save(Resident.newcomer(who, name, CLOCK.instant())).join();
        towns.join(MAYOR, who, riftholm.id()).join();
    }

    private RoleBook book() {
        return store.inTransaction(transaction -> transaction.roles()
                .find(OrganisationScope.TOWN, riftholm.id().value()).orElseThrow()).join();
    }

    /** Creates a role as the mayor and hands it to somebody. */
    private RoleId roleFor(
            final ResidentId holder, final String name, final int priority, final Permission... granted) {
        final RoleId id = roles.create(MAYOR, riftholm.id(), name, priority, Set.of(granted))
                .join().value().orElseThrow().id();
        roles.assign(MAYOR, riftholm.id(), holder, id).join();
        return id;
    }

    @Nested
    @DisplayName("creating and editing")
    class Editing {

        @Test
        @DisplayName("the mayor may create a role, and it appears in rank order")
        void mayorCreatesARole() {
            final ServiceResult<Role> result = roles.create(
                    MAYOR, riftholm.id(), "Officer", 500, Set.of(Permission.CLAIM_LAND)).join();

            assertThat(result.succeeded()).isTrue();
            assertThat(roles.list(riftholm.id()).join()).extracting(Role::name)
                    .containsExactly("Mayor", "Officer", "Resident", "Visitor");
        }

        @Test
        @DisplayName("a member without MANAGE_ROLES cannot create one")
        void memberCannotCreate() {
            addMember(CITIZEN, "Citizen");

            assertThat(roles.create(CITIZEN, riftholm.id(), "Officer", 500, Set.of()).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(book().size()).isEqualTo(3);
        }

        @Test
        @DisplayName("a role is created with its permissions and can be reloaded")
        void createdRolePersists() {
            roles.create(MAYOR, riftholm.id(), "Officer", 500,
                    Set.of(Permission.CLAIM_LAND, Permission.INVITE_RESIDENT)).join();

            assertThat(book().findByName("Officer").orElseThrow().permissions())
                    .containsExactlyInAnyOrder(Permission.CLAIM_LAND, Permission.INVITE_RESIDENT);
        }

        @Test
        @DisplayName("renaming, reprioritising and deleting all work for the mayor")
        void mayorEditsFreely() {
            final RoleId id = roles.create(MAYOR, riftholm.id(), "Officer", 500, Set.of())
                    .join().value().orElseThrow().id();

            assertThat(roles.rename(MAYOR, riftholm.id(), id, "Marshal").join().succeeded()).isTrue();
            assertThat(roles.reprioritise(MAYOR, riftholm.id(), id, 600).join().succeeded()).isTrue();
            assertThat(book().find(id).orElseThrow().priority()).isEqualTo(600);
            assertThat(roles.delete(MAYOR, riftholm.id(), id).join().succeeded()).isTrue();
            assertThat(book().find(id)).isEmpty();
        }

        @Test
        @DisplayName("a system role cannot be deleted through the service either")
        void systemRolesSurviveTheService() {
            final RoleId member = book().systemRole(SystemRole.MEMBER).orElseThrow().id();

            assertThat(roles.delete(MAYOR, riftholm.id(), member).join().denial())
                    .contains(ChangeDenial.SYSTEM_ROLE_CANNOT_BE_DELETED);
        }

        @Test
        @DisplayName("an administrator-locked permission cannot be built into a role")
        void adminLockIsHonoured() {
            final TownRoleService locked =
                    new TownRoleService(store, CLOCK, Set.of(Permission.DISBAND));

            assertThat(locked.create(MAYOR, riftholm.id(), "Officer", 500, Set.of(Permission.DISBAND))
                    .join().denial())
                    .contains(ChangeDenial.PERMISSION_LOCKED_BY_ADMIN);
        }

        @Test
        @DisplayName("editing an unknown role is refused rather than crashing")
        void unknownRoleIsRefused() {
            assertThat(roles.delete(MAYOR, riftholm.id(), RoleId.random()).join().denial())
                    .contains(ChangeDenial.ROLE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("escalation guards")
    class Escalation {

        @Test
        @DisplayName("an officer may not put a permission into a role that they do not hold")
        void cannotGrantWhatYouDoNotHold() {
            addMember(OFFICER, "Officer");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES);

            final ServiceResult<Role> result = roles.create(
                    OFFICER, riftholm.id(), "Steward", 300, Set.of(Permission.DISBAND)).join();

            assertThat(result.denial())
                    .as("otherwise MANAGE_ROLES alone is a way of granting yourself anything")
                    .contains(ChangeDenial.CANNOT_GRANT_UNHELD_PERMISSION);
        }

        @Test
        @DisplayName("an officer may grant a permission they do hold")
        void mayGrantWhatYouHold() {
            addMember(OFFICER, "Officer");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES, Permission.CLAIM_LAND);

            assertThat(roles.create(OFFICER, riftholm.id(), "Steward", 300,
                    Set.of(Permission.CLAIM_LAND)).join().succeeded()).isTrue();
        }

        @Test
        @DisplayName("an officer may not create a role at or above their own rank")
        void cannotCreateAboveSelf() {
            addMember(OFFICER, "Officer");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES);

            assertThat(roles.create(OFFICER, riftholm.id(), "Marshal", 500, Set.of()).join().denial())
                    .contains(ChangeDenial.CANNOT_CREATE_ROLE_ABOVE_SELF);
            assertThat(roles.create(OFFICER, riftholm.id(), "Marshal", 700, Set.of()).join().denial())
                    .contains(ChangeDenial.CANNOT_CREATE_ROLE_ABOVE_SELF);
        }

        @Test
        @DisplayName("an officer may not move a role they outrank to a rank above themselves")
        void cannotReprioritiseAboveSelf() {
            addMember(OFFICER, "Officer");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES);
            final RoleId junior = roles.create(MAYOR, riftholm.id(), "Junior", 200, Set.of())
                    .join().value().orElseThrow().id();

            assertThat(roles.reprioritise(OFFICER, riftholm.id(), junior, 900).join().denial())
                    .contains(ChangeDenial.CANNOT_CREATE_ROLE_ABOVE_SELF);
        }

        @Test
        @DisplayName("an officer may not edit the role above them")
        void cannotEditHigherRole() {
            addMember(OFFICER, "Officer");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES);
            final RoleId marshal = roles.create(MAYOR, riftholm.id(), "Marshal", 700, Set.of())
                    .join().value().orElseThrow().id();

            assertThat(roles.rename(OFFICER, riftholm.id(), marshal, "Nobody").join().denial())
                    .contains(ChangeDenial.INSUFFICIENT_ROLE_PRIORITY);
        }

        @Test
        @DisplayName("an officer may not edit their own role, or they could promote themselves")
        void cannotEditOwnRole() {
            addMember(OFFICER, "Officer");
            final RoleId own = roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES);

            assertThat(roles.grant(OFFICER, riftholm.id(), own, Permission.MANAGE_ROLES)
                    .join().denial())
                    .contains(ChangeDenial.INSUFFICIENT_ROLE_PRIORITY);
        }

        @Test
        @DisplayName("cloning carries the same bound as granting, since it copies permissions")
        void cloningIsBoundedToo() {
            addMember(OFFICER, "Officer");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES);
            final RoleId powerful = roles.create(
                            MAYOR, riftholm.id(), "Steward", 300, Set.of(Permission.DISBAND))
                    .join().value().orElseThrow().id();

            assertThat(roles.clone(OFFICER, riftholm.id(), powerful, "Copy", 200).join().denial())
                    .contains(ChangeDenial.CANNOT_GRANT_UNHELD_PERMISSION);
        }

        @Test
        @DisplayName("revoking is not bounded, so a successor can clean up a predecessor's grant")
        void revokeIsNotBounded() {
            addMember(OFFICER, "Officer");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES);
            final RoleId junior = roles.create(
                            MAYOR, riftholm.id(), "Junior", 200, Set.of(Permission.DISBAND))
                    .join().value().orElseThrow().id();

            assertThat(roles.revoke(OFFICER, riftholm.id(), junior, Permission.DISBAND)
                    .join().succeeded())
                    .as("taking a permission away cannot escalate anybody")
                    .isTrue();
            assertThat(book().find(junior).orElseThrow().permissions()).isEmpty();
        }

        @Test
        @DisplayName("an officer may not assign a role carrying permissions they do not hold")
        void cannotAssignWhatYouDoNotHold() {
            addMember(OFFICER, "Officer");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES, Permission.ASSIGN_ROLES);
            // A role a previous mayor loaded with something the current officer lacks. The javadoc
            // on revoke calls this state normal, so it is not a contrived setup.
            final RoleId junior = roles.create(
                            MAYOR, riftholm.id(), "Junior", 200, Set.of(Permission.DISBAND))
                    .join().value().orElseThrow().id();

            assertThat(roles.assign(OFFICER, riftholm.id(), OFFICER, junior).join().denial())
                    .as("handing out authority is the same escalation as authoring it")
                    .contains(ChangeDenial.CANNOT_GRANT_UNHELD_PERMISSION);
            assertThat(towns.permissionsOf(OFFICER, riftholm.id()).join())
                    .doesNotContain(Permission.DISBAND);
        }

        @Test
        @DisplayName("the bound covers handing it to somebody else, not only to yourself")
        void cannotAssignUnheldToAnAccomplice() {
            addMember(OFFICER, "Officer");
            addMember(CITIZEN, "Citizen");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES, Permission.ASSIGN_ROLES);
            final RoleId junior = roles.create(
                            MAYOR, riftholm.id(), "Junior", 200, Set.of(Permission.DISBAND))
                    .join().value().orElseThrow().id();

            assertThat(roles.assign(OFFICER, riftholm.id(), CITIZEN, junior).join().denial())
                    .contains(ChangeDenial.CANNOT_GRANT_UNHELD_PERMISSION);
        }

        @Test
        @DisplayName("the mayor is exempt from every bound, because they already hold everything")
        void mayorIsExempt() {
            assertThat(roles.create(MAYOR, riftholm.id(), "Steward", 900,
                    Set.of(Permission.DISBAND)).join().succeeded()).isTrue();
        }
    }

    @Nested
    @DisplayName("assigning")
    class Assigning {

        @Test
        @DisplayName("the mayor may hand a role to a resident, and it takes effect immediately")
        void assignTakesEffect() {
            addMember(CITIZEN, "Citizen");
            final RoleId id = roles.create(MAYOR, riftholm.id(), "Officer", 500,
                    Set.of(Permission.CLAIM_LAND)).join().value().orElseThrow().id();

            assertThat(roles.assign(MAYOR, riftholm.id(), CITIZEN, id).join().succeeded()).isTrue();
            assertThat(towns.permissionsOf(CITIZEN, riftholm.id()).join())
                    .contains(Permission.CLAIM_LAND);
        }

        @Test
        @DisplayName("a role cannot be given to somebody who is not a resident")
        void outsidersCannotHoldRoles() {
            final RoleId id = roles.create(MAYOR, riftholm.id(), "Officer", 500, Set.of())
                    .join().value().orElseThrow().id();

            assertThat(roles.assign(MAYOR, riftholm.id(), CITIZEN, id).join().denial())
                    .contains(ChangeDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
        }

        @Test
        @DisplayName("assigning needs ASSIGN_ROLES, which MANAGE_ROLES does not imply")
        void assignNeedsItsOwnPermission() {
            addMember(OFFICER, "Officer");
            addMember(CITIZEN, "Citizen");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES);
            final RoleId junior = roles.create(MAYOR, riftholm.id(), "Junior", 200, Set.of())
                    .join().value().orElseThrow().id();

            assertThat(roles.assign(OFFICER, riftholm.id(), CITIZEN, junior).join().denial())
                    .as("editing a role and handing it out are separate powers")
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("an officer with ASSIGN_ROLES may hand out a role below them")
        void officerAssignsBelow() {
            addMember(OFFICER, "Officer");
            addMember(CITIZEN, "Citizen");
            roleFor(OFFICER, "Officer", 500, Permission.ASSIGN_ROLES);
            final RoleId junior = roles.create(MAYOR, riftholm.id(), "Junior", 200, Set.of())
                    .join().value().orElseThrow().id();

            assertThat(roles.assign(OFFICER, riftholm.id(), CITIZEN, junior).join().succeeded())
                    .isTrue();
        }

        @Test
        @DisplayName("an officer may not hand out a role above themselves")
        void officerCannotAssignAbove() {
            addMember(OFFICER, "Officer");
            addMember(CITIZEN, "Citizen");
            roleFor(OFFICER, "Officer", 500, Permission.ASSIGN_ROLES);
            final RoleId marshal = roles.create(MAYOR, riftholm.id(), "Marshal", 700, Set.of())
                    .join().value().orElseThrow().id();

            assertThat(roles.assign(OFFICER, riftholm.id(), CITIZEN, marshal).join().denial())
                    .contains(ChangeDenial.INSUFFICIENT_ROLE_PRIORITY);
        }

        @Test
        @DisplayName("the leader role can never be handed out")
        void leaderRoleIsNotAssignable() {
            addMember(CITIZEN, "Citizen");
            final RoleId leader = book().systemRole(SystemRole.LEADER).orElseThrow().id();

            assertThat(roles.assign(MAYOR, riftholm.id(), CITIZEN, leader).join().denial())
                    .contains(ChangeDenial.LEADER_ROLE_IS_NOT_ASSIGNABLE);
        }

        @Test
        @DisplayName("revoking a role removes its permissions again")
        void unassignRemovesThePermissions() {
            addMember(CITIZEN, "Citizen");
            final RoleId id = roles.create(MAYOR, riftholm.id(), "Officer", 500,
                    Set.of(Permission.CLAIM_LAND)).join().value().orElseThrow().id();
            roles.assign(MAYOR, riftholm.id(), CITIZEN, id).join();

            assertThat(roles.unassign(MAYOR, riftholm.id(), CITIZEN, id).join().succeeded()).isTrue();
            assertThat(towns.permissionsOf(CITIZEN, riftholm.id()).join())
                    .doesNotContain(Permission.CLAIM_LAND);
        }
    }

    @Nested
    @DisplayName("persistence")
    class Persistence {

        @Test
        @DisplayName("a refused edit leaves the book untouched")
        void refusedEditWritesNothing() {
            addMember(OFFICER, "Officer");
            roleFor(OFFICER, "Officer", 500, Permission.MANAGE_ROLES);
            final int before = book().size();

            roles.create(OFFICER, riftholm.id(), "Marshal", 900, Set.of()).join();

            assertThat(book().size()).isEqualTo(before);
            assertThat(book().findByName("Marshal")).isEmpty();
        }

        @Test
        @DisplayName("every accepted edit queues an announcement")
        void editsAreAnnounced() {
            final JdbcOutboxRepository outbox = new JdbcOutboxRepository(database, DIRECT);
            final long before = outbox.counts().join().total();

            roles.create(MAYOR, riftholm.id(), "Officer", 500, Set.of()).join();

            assertThat(outbox.counts().join().total()).isEqualTo(before + 1);
        }
    }
}
