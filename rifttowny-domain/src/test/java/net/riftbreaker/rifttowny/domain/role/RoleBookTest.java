package net.riftbreaker.rifttowny.domain.role;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Outcome;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleBookTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final UUID TOWN = UUID.randomUUID();

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId OFFICER = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());
    private static final ResidentId STRANGER = ResidentId.of(UUID.randomUUID());

    private static RoleBook book() {
        return RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW);
    }

    private static Role officerRole(final int priority, final Permission... permissions) {
        return Role.custom(
                RoleId.random(), OrganisationScope.TOWN, TOWN, "Officer", priority,
                Set.of(permissions), NOW);
    }

    @Nested
    @DisplayName("a new organisation")
    class Defaults {

        @Test
        @DisplayName("starts with exactly the three system roles")
        void startsWithThreeSystemRoles() {
            final RoleBook book = book();

            assertThat(book.size()).isEqualTo(3);
            assertThat(book.ordered()).extracting(Role::name)
                    .containsExactly("Mayor", "Resident", "Visitor");
            assertThat(book.ordered()).allMatch(Role::isSystem);
        }

        @Test
        @DisplayName("names the leader and member roles for the scope")
        void nationRolesAreNamedDifferently() {
            final RoleBook nation =
                    RoleBook.defaultsFor(OrganisationScope.NATION, UUID.randomUUID(), NOW);

            assertThat(nation.ordered()).extracting(Role::name)
                    .containsExactly("King", "Citizen", "Visitor");
        }

        @Test
        @DisplayName("gives the leader every permission, including ones added later")
        void leaderHoldsEverything() {
            final Role leader = book().systemRole(SystemRole.LEADER).orElseThrow();

            assertThat(leader.permissions()).containsExactlyInAnyOrder(Permission.values());
        }

        @Test
        @DisplayName("gives a visitor almost nothing, and never a management permission")
        void visitorIsMinimal() {
            final Role visitor = book().systemRole(SystemRole.VISITOR).orElseThrow();

            assertThat(visitor.permissions()).containsExactly(Permission.SHOP_USE);
            assertThat(visitor.permissions()).noneMatch(Permission::isManagement);
        }

        @Test
        @DisplayName("gives an ordinary member no management permission at all")
        void memberHoldsNoManagement() {
            final Role member = book().systemRole(SystemRole.MEMBER).orElseThrow();

            assertThat(member.permissions()).isNotEmpty();
            assertThat(member.permissions()).noneMatch(Permission::isManagement);
        }

        @Test
        @DisplayName("restoring without a system role fails loudly")
        void corruptRestoreIsRefused() {
            assertThatThrownBy(() -> RoleBook.restore(
                    OrganisationScope.TOWN, TOWN, List.of(officerRole(500)), java.util.Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("LEADER");
        }
    }

    @Nested
    @DisplayName("editing roles")
    class Editing {

        @Test
        @DisplayName("a configurable role can be added and is ranked between the system roles")
        void createRanksTheRole() {
            final RoleBook book = book().create(officerRole(500), Set.of()).orElseThrow();

            assertThat(book.ordered()).extracting(Role::name)
                    .containsExactly("Mayor", "Officer", "Resident", "Visitor");
        }

        @Test
        @DisplayName("a duplicate name is refused, case-insensitively")
        void duplicateNameIsRefused() {
            final RoleBook book = book().create(officerRole(500), Set.of()).orElseThrow();
            final Role clash = Role.custom(
                    RoleId.random(), OrganisationScope.TOWN, TOWN, "officer", 400, Set.of(), NOW);

            assertThat(book.create(clash, Set.of()).denial()).contains(ChangeDenial.ROLE_NAME_TAKEN);
        }

        @Test
        @DisplayName("a duplicate priority is refused, so the ranking stays a total order")
        void duplicatePriorityIsRefused() {
            final RoleBook book = book().create(officerRole(500), Set.of()).orElseThrow();
            final Role clash = Role.custom(
                    RoleId.random(), OrganisationScope.TOWN, TOWN, "Marshal", 500, Set.of(), NOW);

            assertThat(book.create(clash, Set.of()).denial())
                    .contains(ChangeDenial.PRIORITY_ALREADY_USED);
        }

        @Test
        @DisplayName("nothing may be created at or above the leader's rank")
        void nothingOutranksTheLeader() {
            assertThat(book().create(officerRole(SystemRole.LEADER.priority()), Set.of()).denial())
                    .contains(ChangeDenial.PRIORITY_RESERVED_FOR_LEADER);
            assertThat(book().create(officerRole(SystemRole.LEADER.priority() + 1), Set.of()).denial())
                    .contains(ChangeDenial.PRIORITY_RESERVED_FOR_LEADER);
        }

        @Test
        @DisplayName("a permission the administrator locked cannot be built into a role")
        void lockedPermissionsAreRefusedAtCreation() {
            final Role dangerous = officerRole(500, Permission.DISBAND);

            assertThat(book().create(dangerous, Set.of(Permission.DISBAND)).denial())
                    .contains(ChangeDenial.PERMISSION_LOCKED_BY_ADMIN);
        }

        @Test
        @DisplayName("a locked permission cannot be granted afterwards either")
        void lockedPermissionsAreRefusedAtGrant() {
            final Role officer = officerRole(500);
            final RoleBook book = book().create(officer, Set.of()).orElseThrow();

            assertThat(book.grant(officer.id(), Permission.DISBAND, Set.of(Permission.DISBAND))
                    .denial()).contains(ChangeDenial.PERMISSION_LOCKED_BY_ADMIN);
        }

        @Test
        @DisplayName("a system role cannot be deleted")
        void systemRolesSurvive() {
            final RoleBook book = book();
            final RoleId member = book.systemRole(SystemRole.MEMBER).orElseThrow().id();

            assertThat(book.delete(member).denial())
                    .contains(ChangeDenial.SYSTEM_ROLE_CANNOT_BE_DELETED);
        }

        @Test
        @DisplayName("a system role may be renamed, because a server may call its leader Jarl")
        void systemRolesMayBeRenamed() {
            final RoleBook book = book();
            final RoleId leader = book.systemRole(SystemRole.LEADER).orElseThrow().id();

            final RoleBook renamed = book.rename(leader, "Jarl").orElseThrow();

            assertThat(renamed.systemRole(SystemRole.LEADER).orElseThrow().name()).isEqualTo("Jarl");
        }

        @Test
        @DisplayName("a system role's priority is fixed")
        void systemRolePriorityIsFixed() {
            final RoleBook book = book();
            final RoleId member = book.systemRole(SystemRole.MEMBER).orElseThrow().id();

            assertThat(book.reprioritise(member, 400).denial())
                    .contains(ChangeDenial.SYSTEM_ROLE_PRIORITY_IS_FIXED);
        }

        @Test
        @DisplayName("the leader's permissions cannot be edited, so a town cannot lock itself out")
        void leaderPermissionsAreFixed() {
            final RoleBook book = book();
            final RoleId leader = book.systemRole(SystemRole.LEADER).orElseThrow().id();

            assertThat(book.revoke(leader, Permission.ASSIGN_ROLES).denial())
                    .contains(ChangeDenial.LEADER_PERMISSIONS_ARE_FIXED);
            assertThat(book.grant(leader, Permission.BUILD, Set.of()).denial())
                    .contains(ChangeDenial.LEADER_PERMISSIONS_ARE_FIXED);
        }

        @Test
        @DisplayName("deleting a role revokes it from everyone holding it")
        void deletingARoleCleansUpAssignments() {
            final Role officer = officerRole(500);
            final RoleBook book = book().create(officer, Set.of()).orElseThrow()
                    .assign(OFFICER, officer.id()).orElseThrow();
            assertThat(book.rolesOf(OFFICER)).containsExactly(officer.id());

            final RoleBook deleted = book.delete(officer.id()).orElseThrow();

            assertThat(deleted.rolesOf(OFFICER))
                    .as("a dangling assignment would grant something unexpected if the id were reused")
                    .isEmpty();
        }

        @Test
        @DisplayName("every edit emits an event naming the role and what happened")
        void editsEmitEvents() {
            final Role officer = officerRole(500);
            final Outcome<RoleBook> created = book().create(officer, Set.of());

            assertThat(created.events()).singleElement()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                            .type(DomainEvent.RoleChanged.class))
                    .satisfies(event -> {
                        assertThat(event.action()).isEqualTo(DomainEvent.RoleAction.CREATED);
                        assertThat(event.roleName()).isEqualTo("Officer");
                        assertThat(event.type()).isEqualTo("role.created");
                    });
        }
    }

    @Nested
    @DisplayName("assigning roles")
    class Assigning {

        @Test
        @DisplayName("a configurable role can be granted and revoked")
        void assignAndUnassign() {
            final Role officer = officerRole(500);
            final RoleBook withRole = book().create(officer, Set.of()).orElseThrow();

            final RoleBook assigned = withRole.assign(OFFICER, officer.id()).orElseThrow();
            assertThat(assigned.rolesOf(OFFICER)).containsExactly(officer.id());
            assertThat(assigned.holdersOf(officer.id())).containsExactly(OFFICER);

            assertThat(assigned.unassign(OFFICER, officer.id()).orElseThrow().rolesOf(OFFICER))
                    .isEmpty();
        }

        @Test
        @DisplayName("the leader role is never assignable, because leadership transfer owns it")
        void leaderRoleIsNotAssignable() {
            final RoleBook book = book();
            final RoleId leader = book.systemRole(SystemRole.LEADER).orElseThrow().id();

            assertThat(book.assign(OFFICER, leader).denial())
                    .contains(ChangeDenial.LEADER_ROLE_IS_NOT_ASSIGNABLE);
        }

        @Test
        @DisplayName("the member role is never assignable, because residency grants it")
        void memberRoleIsNotAssignable() {
            final RoleBook book = book();
            final RoleId member = book.systemRole(SystemRole.MEMBER).orElseThrow().id();

            assertThat(book.assign(OFFICER, member).denial())
                    .contains(ChangeDenial.BASELINE_ROLE_IS_NOT_ASSIGNABLE);
        }

        @Test
        @DisplayName("granting the same role twice is refused rather than silently repeated")
        void doubleAssignIsRefused() {
            final Role officer = officerRole(500);
            final RoleBook book = book().create(officer, Set.of()).orElseThrow()
                    .assign(OFFICER, officer.id()).orElseThrow();

            assertThat(book.assign(OFFICER, officer.id()).denial())
                    .contains(ChangeDenial.ALREADY_HAS_ROLE);
        }

        @Test
        @DisplayName("revoking a role nobody holds is refused")
        void unassignWithoutTheRole() {
            final Role officer = officerRole(500);
            final RoleBook book = book().create(officer, Set.of()).orElseThrow();

            assertThat(book.unassign(OFFICER, officer.id()).denial())
                    .contains(ChangeDenial.DOES_NOT_HAVE_ROLE);
        }
    }

    @Nested
    @DisplayName("resolving what someone may do")
    class Resolution {

        @Test
        @DisplayName("the leader may do everything without consulting a role")
        void leaderMayDoAnything() {
            final RoleBook book = book();

            for (final Permission permission : Permission.values()) {
                assertThat(book.allows(MAYOR, permission, SystemRole.LEADER))
                        .as("leader and %s", permission)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a member gets the baseline and nothing more")
        void memberGetsTheBaseline() {
            final RoleBook book = book();

            assertThat(book.allows(CITIZEN, Permission.BUILD, SystemRole.MEMBER)).isTrue();
            assertThat(book.allows(CITIZEN, Permission.CLAIM_LAND, SystemRole.MEMBER)).isFalse();
        }

        @Test
        @DisplayName("a stranger gets the visitor baseline")
        void strangerGetsVisitor() {
            final RoleBook book = book();

            assertThat(book.allows(STRANGER, Permission.SHOP_USE, SystemRole.VISITOR)).isTrue();
            assertThat(book.allows(STRANGER, Permission.BUILD, SystemRole.VISITOR)).isFalse();
        }

        @Test
        @DisplayName("roles are additive, so a narrow role never removes what membership granted")
        void rolesAreAdditive() {
            final Role farmer = Role.custom(
                    RoleId.random(), OrganisationScope.TOWN, TOWN, "Farmer", 200,
                    Set.of(Permission.FARMLAND), NOW);
            final RoleBook book = book().create(farmer, Set.of()).orElseThrow()
                    .assign(CITIZEN, farmer.id()).orElseThrow();

            assertThat(book.allows(CITIZEN, Permission.FARMLAND, SystemRole.MEMBER)).isTrue();
            assertThat(book.allows(CITIZEN, Permission.BUILD, SystemRole.MEMBER))
                    .as("the farmer role must not have replaced the member baseline")
                    .isTrue();
        }

        @Test
        @DisplayName("an assigned role adds management permissions the baseline lacks")
        void assignedRolesAddManagement() {
            final Role officer = officerRole(500, Permission.CLAIM_LAND, Permission.INVITE_RESIDENT);
            final RoleBook book = book().create(officer, Set.of()).orElseThrow()
                    .assign(OFFICER, officer.id()).orElseThrow();

            assertThat(book.allows(OFFICER, Permission.CLAIM_LAND, SystemRole.MEMBER)).isTrue();
            assertThat(book.allows(CITIZEN, Permission.CLAIM_LAND, SystemRole.MEMBER)).isFalse();
        }

        @Test
        @DisplayName("the effective set is the union of baseline and assigned roles")
        void effectiveSetIsAUnion() {
            final Role officer = officerRole(500, Permission.CLAIM_LAND);
            final RoleBook book = book().create(officer, Set.of()).orElseThrow()
                    .assign(OFFICER, officer.id()).orElseThrow();

            assertThat(book.effectivePermissions(OFFICER, SystemRole.MEMBER))
                    .contains(Permission.CLAIM_LAND, Permission.BUILD)
                    .doesNotContain(Permission.DISBAND);
        }
    }

    @Nested
    @DisplayName("rank and who may manage whom")
    class Rank {

        @Test
        @DisplayName("rank is the highest role held, so a low role never demotes anyone")
        void rankIsTheHighestNotTheUnion() {
            final Role junior = Role.custom(
                    RoleId.random(), OrganisationScope.TOWN, TOWN, "Junior", 50, Set.of(), NOW);
            final Role officer = officerRole(500);
            final RoleBook book = book()
                    .create(officer, Set.of()).orElseThrow()
                    .create(junior, Set.of()).orElseThrow()
                    .assign(OFFICER, officer.id()).orElseThrow()
                    .assign(OFFICER, junior.id()).orElseThrow();

            assertThat(book.rankOf(OFFICER, SystemRole.MEMBER)).isEqualTo(500);
        }

        @Test
        @DisplayName("a member's rank is the member baseline")
        void baselineIsTheFloor() {
            assertThat(book().rankOf(CITIZEN, SystemRole.MEMBER))
                    .isEqualTo(SystemRole.MEMBER.priority());
        }

        @Test
        @DisplayName("the leader may manage every role")
        void leaderManagesEverything() {
            final Role officer = officerRole(500);
            final RoleBook book = book().create(officer, Set.of()).orElseThrow();

            assertThat(book.mayManage(MAYOR, SystemRole.LEADER, officer.id())).isTrue();
        }

        @Test
        @DisplayName("an officer may manage a role below them")
        void officerManagesLowerRoles() {
            final Role officer = officerRole(500);
            final Role junior = Role.custom(
                    RoleId.random(), OrganisationScope.TOWN, TOWN, "Junior", 200, Set.of(), NOW);
            final RoleBook book = book()
                    .create(officer, Set.of()).orElseThrow()
                    .create(junior, Set.of()).orElseThrow()
                    .assign(OFFICER, officer.id()).orElseThrow();

            assertThat(book.mayManage(OFFICER, SystemRole.MEMBER, junior.id())).isTrue();
        }

        @Test
        @DisplayName("an officer may not manage their own role, or they could promote themselves")
        void officerCannotManageTheirOwnRole() {
            final Role officer = officerRole(500);
            final RoleBook book = book().create(officer, Set.of()).orElseThrow()
                    .assign(OFFICER, officer.id()).orElseThrow();

            assertThat(book.mayManage(OFFICER, SystemRole.MEMBER, officer.id()))
                    .as("equal rank is refused, or two equals could demote each other in a loop")
                    .isFalse();
        }

        @Test
        @DisplayName("an officer may not manage a role above them")
        void officerCannotManageHigherRoles() {
            final Role officer = officerRole(500);
            final Role marshal = Role.custom(
                    RoleId.random(), OrganisationScope.TOWN, TOWN, "Marshal", 700, Set.of(), NOW);
            final RoleBook book = book()
                    .create(officer, Set.of()).orElseThrow()
                    .create(marshal, Set.of()).orElseThrow()
                    .assign(OFFICER, officer.id()).orElseThrow();

            assertThat(book.mayManage(OFFICER, SystemRole.MEMBER, marshal.id())).isFalse();
        }

        @Test
        @DisplayName("nobody may manage the leader role")
        void nobodyManagesTheLeaderRole() {
            final Role officer = officerRole(999);
            final RoleBook book = book().create(officer, Set.of()).orElseThrow()
                    .assign(OFFICER, officer.id()).orElseThrow();
            final RoleId leader = book.systemRole(SystemRole.LEADER).orElseThrow().id();

            assertThat(book.mayManage(OFFICER, SystemRole.MEMBER, leader))
                    .as("the highest configurable rank is still below the leader")
                    .isFalse();
        }
    }

    /**
     * The display name, icon and chat prefix.
     *
     * <p>Three columns that were written on every role and settable by nothing: only
     * {@code Role.decorate} could change them and its one caller was a storage test. The rename bug
     * below is what made fixing this urgent rather than tidy — {@code /town role rename} shipped
     * before anything read the display name, so it had been quietly writing a stale one.</p>
     */
    @Nested
    @DisplayName("decorating roles")
    class Decorating {

        private RoleId officer(final RoleBook book) {
            return book.findByName("Officer").orElseThrow().id();
        }

        private RoleBook withOfficer() {
            return book().create(officerRole(500), Set.of()).orElseThrow();
        }

        @Test
        @DisplayName("a renamed role takes its display name with it")
        void renameCarriesTheDisplayName() {
            // The bug: renameTo kept the old displayName beside the new name, so a role renamed
            // from Mayor to Jarl still displayed as Mayor. Invisible only because nothing read the
            // column - and /town role rename now exists, so rows were being written wrong.
            final RoleBook book = book();
            final RoleId leader = book.systemRole(SystemRole.LEADER).orElseThrow().id();

            final Role renamed = book.rename(leader, "Jarl").orElseThrow()
                    .systemRole(SystemRole.LEADER).orElseThrow();

            assertThat(renamed.name()).isEqualTo("Jarl");
            assertThat(renamed.displayName())
                    .as("a display name nobody chose is not a decision worth preserving")
                    .isEqualTo("Jarl");
        }

        @Test
        @DisplayName("but a display name somebody chose survives a rename")
        void renameKeepsAChosenDisplayName() {
            // The other half, and the reason this is not simply "displayName = newName".
            final RoleBook book = withOfficer();
            final RoleId officer = officer(book);

            final Role after = book.decorate(officer, "Reeve of the Marches", null, null)
                    .orElseThrow()
                    .rename(officer, "Warden").orElseThrow()
                    .find(officer).orElseThrow();

            assertThat(after.name()).isEqualTo("Warden");
            assertThat(after.displayName()).isEqualTo("Reeve of the Marches");
        }

        @Test
        @DisplayName("sets all three, and leaves the name alone")
        void setsTheThree() {
            final RoleBook book = withOfficer();
            final RoleId officer = officer(book);

            final Role after = book.decorate(officer, "Captain", "sword", "[Cpt]")
                    .orElseThrow().find(officer).orElseThrow();

            assertThat(after.name()).as("nothing that refers to it by name may break").isEqualTo("Officer");
            assertThat(after.displayName()).isEqualTo("Captain");
            assertThat(after.icon()).contains("sword");
            assertThat(after.chatPrefix()).contains("[Cpt]");
        }

        @Test
        @DisplayName("a blank display name falls back to the name, which is how it is cleared")
        void blankFallsBackToTheName() {
            final RoleBook book = withOfficer();
            final RoleId officer = officer(book);

            final Role after = book.decorate(officer, "Captain", null, null).orElseThrow()
                    .decorate(officer, "  ", null, null).orElseThrow()
                    .find(officer).orElseThrow();

            assertThat(after.displayName()).isEqualTo("Officer");
        }

        @Test
        @DisplayName("a system role may be decorated, like it may be renamed")
        void systemRolesMayBeDecorated() {
            final RoleBook book = book();
            final RoleId leader = book.systemRole(SystemRole.LEADER).orElseThrow().id();

            assertThat(book.decorate(leader, "Jarl", null, "[J]").wasApplied()).isTrue();
        }

        @Test
        @DisplayName("decorating a role that is not there is refused")
        void unknownRoleIsRefused() {
            assertThat(book().decorate(RoleId.random(), "Captain", null, null).denial())
                    .contains(ChangeDenial.ROLE_NOT_FOUND);
        }
    }
}
