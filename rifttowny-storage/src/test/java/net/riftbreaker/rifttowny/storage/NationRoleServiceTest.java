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
    private CivicCache civicCache;
    private net.riftbreaker.rifttowny.domain.civic.NationCache nationCache;
    private CivicCacheService civic;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        civicCache = CivicCache.empty();
        nationCache = net.riftbreaker.rifttowny.domain.civic.NationCache.empty();
        civic = new CivicCacheService(
                store, civicCache, nationCache,
                net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook.empty(),
                net.riftbreaker.rifttowny.domain.justice.Outlaws.empty(), warning -> { });
        towns = new TownService(
                store, NamePolicy.defaults(), CLOCK, TerritoryIndex.empty(), civic);
        nations = new NationService(store, NamePolicy.defaults(), CLOCK, civic);
        roles = new NationRoleService(store, CLOCK, Set.of(), civic);
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

    @Nested
    @DisplayName("a role does not outlive the citizenship that justified it")
    class LosingCitizenship {

        @Test
        @DisplayName("leaving your town takes your nation role with it")
        void leavingATown() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ALLEGIANCE);
            residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
            final TownId ashford = ashfordOf(nation);
            towns.join(MAYOR, CITIZEN, ashford).join();
            roles.assign(KING, nation.id(), CITIZEN, envoy).join();
            assertThat(heldRoles(nation, CITIZEN)).contains(envoy);

            towns.leave(CITIZEN, ashford).join();

            assertThat(heldRoles(nation, CITIZEN))
                    .as("otherwise a stranger keeps an office in a nation they left")
                    .isEmpty();
        }

        @Test
        @DisplayName("being kicked from your town takes it too")
        void beingKicked() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ALLEGIANCE);
            residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
            final TownId ashford = ashfordOf(nation);
            towns.join(MAYOR, CITIZEN, ashford).join();
            roles.assign(KING, nation.id(), CITIZEN, envoy).join();

            towns.kick(MAYOR, CITIZEN, ashford).join();

            assertThat(heldRoles(nation, CITIZEN)).isEmpty();
        }

        @Test
        @DisplayName("a town leaving the nation strips every one of its residents")
        void theirTownLeaving() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ALLEGIANCE);
            roles.assign(KING, nation.id(), MAYOR, envoy).join();

            nations.leave(MAYOR, townOf(MAYOR)).join();

            assertThat(heldRoles(nation, MAYOR))
                    .as("their town is not in the nation any more, so neither are they")
                    .isEmpty();
        }

        @Test
        @DisplayName("a town expelled from the nation loses them as well")
        void theirTownExpelled() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ALLEGIANCE);
            roles.assign(KING, nation.id(), MAYOR, envoy).join();

            nations.expel(KING, nation.id(), townOf(MAYOR)).join();

            assertThat(heldRoles(nation, MAYOR)).isEmpty();
        }

        @Test
        @DisplayName("a disbanded town's residents lose them")
        void theirTownDisbanded() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ALLEGIANCE);
            roles.assign(KING, nation.id(), MAYOR, envoy).join();

            towns.disband(MAYOR, townOf(MAYOR)).join();

            assertThat(heldRoles(nation, MAYOR)).isEmpty();
        }

        @Test
        @DisplayName("rejoining does not hand the role back")
        void rejoiningDoesNotRestoreIt() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ALLEGIANCE);
            residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
            final TownId ashford = ashfordOf(nation);
            towns.join(MAYOR, CITIZEN, ashford).join();
            roles.assign(KING, nation.id(), CITIZEN, envoy).join();
            towns.leave(CITIZEN, ashford).join();

            towns.join(MAYOR, CITIZEN, ashford).join();

            assertThat(heldRoles(nation, CITIZEN))
                    .as("granting it again is the nation's decision to make")
                    .isEmpty();
        }

        @Test
        @DisplayName("somebody else's departure leaves your role alone")
        void othersAreUnaffected() {
            final Nation nation = valen();
            final RoleId envoy = envoy(nation, Permission.MANAGE_ALLEGIANCE);
            residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
            final TownId ashford = ashfordOf(nation);
            towns.join(MAYOR, CITIZEN, ashford).join();
            roles.assign(KING, nation.id(), MAYOR, envoy).join();

            towns.leave(CITIZEN, ashford).join();

            assertThat(heldRoles(nation, MAYOR)).contains(envoy);
        }
    }

    /**
     * Ashford's id, which valen() admitted.
     *
     * <p>Re-read rather than taken from the returned aggregate: {@code valen()} hands back the
     * nation as it was at founding, before it admitted anybody.</p>
     */
    private TownId ashfordOf(final Nation nation) {
        final Nation current =
                store.inTransaction(t -> t.nations().find(nation.id()).orElseThrow()).join();
        return current.towns().stream()
                .filter(town -> !town.equals(current.capital()))
                .findFirst()
                .orElseThrow();
    }

    private TownId townOf(final ResidentId who) {
        return store.inTransaction(t -> t.residents().find(who).orElseThrow().town().orElseThrow())
                .join();
    }

    private Set<RoleId> heldRoles(final Nation nation, final ResidentId who) {
        return store.inTransaction(transaction -> transaction.roles()
                .find(OrganisationScope.NATION, nation.id().value())
                .map(book -> book.rolesOf(who))
                .orElse(Set.of())).join();
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
    /**
     * The nation cache, which now carries role books.
     *
     * <p>It did not until chat needed CHAT_NATION and a nation role's prefix answered inside
     * AsyncChatEvent, where a query is not available. Caching them means every path that can change
     * a nation's roles has to say so, and the ones that end citizenship are the awkward half: they
     * are town operations - somebody leaves, somebody is purged, a town is disbanded - that revoke
     * nation roles through CitizenRoles without otherwise touching the nation at all. Each of those
     * is pinned here, because a missed one is somebody keeping an audience their nation took back,
     * and nothing would say so until a restart.</p>
     */
    @Nested
    @DisplayName("the nation cache")
    class Caching {

        private boolean cachedAllows(
                final Nation nation, final ResidentId who, final Permission permission) {
            return nationCache.facts(nation.id())
                    .map(facts -> facts.allows(who, permission, civicCache.townOf(who).orElse(null)))
                    .orElse(false);
        }

        @Test
        @DisplayName("a role edit reaches it, so a revoked permission is answered from memory")
        void roleEditsRefreshTheCache() {
            final Nation nation = valen();
            civic.loadAll().join();

            assertThat(cachedAllows(nation, MAYOR, Permission.CHAT_NATION))
                    .as("MEMBER holds it by default")
                    .isTrue();

            final RoleId member = nationCache.facts(nation.id()).orElseThrow().roles()
                    .systemRole(net.riftbreaker.rifttowny.domain.role.SystemRole.MEMBER)
                    .orElseThrow().id();
            roles.revoke(KING, nation.id(), member, Permission.CHAT_NATION).join();

            assertThat(cachedAllows(nation, MAYOR, Permission.CHAT_NATION))
                    .as("the edit must reach the cache, not wait for a restart")
                    .isFalse();
        }

        @Test
        @DisplayName("a resident leaving their town loses their nation role in the cache too")
        void leavingRefreshesTheNation() {
            // The path with no reason of its own to think about nations: TownService.release
            // revokes nation roles through CitizenRoles and refreshed only the town.
            //
            // The assertion is deliberately about an ASSIGNED role rather than about CHAT_NATION.
            // Losing the town alone turns their nation standing into VISITOR, so a MEMBER-default
            // permission reads false either way and the test would pass with no nation refresh at
            // all - which is exactly what the first version of it did.
            final Nation nation = valen();
            final ResidentId envoy = ResidentId.of(UUID.randomUUID());
            residents.save(Resident.newcomer(envoy, "Envoy", NOW)).join();
            final TownId ashford = civicCache.townOf(MAYOR).orElseThrow();
            towns.join(MAYOR, envoy, ashford).join();
            final RoleId role = envoy(nation, Permission.MANAGE_TAXES);
            roles.assign(KING, nation.id(), envoy, role).join();
            civic.loadAll().join();
            assertThat(cachedAllows(nation, envoy, Permission.MANAGE_TAXES))
                    .as("the assigned role grants it regardless of standing")
                    .isTrue();

            towns.leave(envoy, ashford).join();

            assertThat(cachedAllows(nation, envoy, Permission.MANAGE_TAXES))
                    .as("the role was revoked with their citizenship, and the cache must know")
                    .isFalse();
        }

        @Test
        @DisplayName("and a member town disbanding reaches it as well")
        void disbandRefreshesTheNation() {
            final Nation nation = valen();
            civic.loadAll().join();
            final TownId ashford = civicCache.townOf(MAYOR).orElseThrow();
            assertThat(nationCache.facts(nation.id()).orElseThrow().nation().hasTown(ashford))
                    .isTrue();

            towns.disband(MAYOR, ashford).join();

            assertThat(nationCache.facts(nation.id()).orElseThrow().nation().hasTown(ashford))
                    .as("a dissolved town must not stay a member in memory")
                    .isFalse();
        }
    }

}
