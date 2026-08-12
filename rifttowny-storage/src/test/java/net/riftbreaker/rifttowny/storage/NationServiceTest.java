package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Invitation;
import net.riftbreaker.rifttowny.domain.org.Nation;
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
import net.riftbreaker.rifttowny.domain.service.NationService;
import net.riftbreaker.rifttowny.domain.service.TownRoleService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nations against a real database.
 *
 * <p>The two-sided join is the point of most of this. Everything else follows the town service's
 * shape; the invitation is the part that is new, and the part where getting it wrong lets a nation
 * walk into somebody else's territory.
 */
class NationServiceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final ResidentId KING = ResidentId.of(UUID.randomUUID());
    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());

    private final TerritoryIndex index = TerritoryIndex.empty();
    private final CivicCache civicCache = CivicCache.empty();

    private JdbcCivicStore store;
    private NationService nations;
    private TownService towns;
    private TownRoleService townRoles;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        final CivicCacheService civic = new CivicCacheService(store, civicCache, warning -> { });
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, index, civic);
        townRoles = new TownRoleService(store, CLOCK, Set.of(), civic);
        nations = new NationService(store, NamePolicy.defaults(), CLOCK, civic);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private Town town(final ResidentId mayor, final String name) {
        residents.save(Resident.newcomer(mayor, name + "Mayor", NOW)).join();
        return towns.found(mayor, name + "Mayor", name).join().value().orElseThrow();
    }

    /** Valen, founded on Riftholm, with Ashford standing outside it. */
    private Nation valen() {
        final Town riftholm = town(KING, "Riftholm");
        return nations.found(KING, riftholm.id(), "Valen").join().value().orElseThrow();
    }

    private TownId ashford() {
        return town(MAYOR, "Ashford").id();
    }

    @Nested
    @DisplayName("founding")
    class Founding {

        @Test
        @DisplayName("a nation is founded on a town, which becomes its capital and only member")
        void founding() {
            final Town riftholm = town(KING, "Riftholm");

            final Nation nation =
                    nations.found(KING, riftholm.id(), "Valen").join().value().orElseThrow();

            assertThat(nation.capital()).isEqualTo(riftholm.id());
            assertThat(nation.towns()).containsExactly(riftholm.id());
            assertThat(nation.leader()).isEqualTo(KING);
            assertThat(store.inTransaction(t -> t.towns().find(riftholm.id()).orElseThrow().nation())
                    .join()).contains(nation.id());
        }

        @Test
        @DisplayName("founding creates the nation's role book, so it can answer a permission question")
        void foundingCreatesRoles() {
            final Nation nation = valen();

            assertThat(store.inTransaction(t ->
                    t.roles().find(OrganisationScope.NATION, nation.id().value())).join())
                    .isPresent();
        }

        @Test
        @DisplayName("a town already in a nation cannot found another")
        void oneNationPerTown() {
            final Nation nation = valen();

            assertThat(nations.found(KING, nation.capital(), "Korath").join().denial())
                    .contains(ChangeDenial.TOWN_ALREADY_IN_ANOTHER_NATION);
        }

        @Test
        @DisplayName("a resident without MANAGE_ALLEGIANCE in the town cannot commit it to a nation")
        void foundingNeedsTheTownsConsent() {
            final Town riftholm = town(KING, "Riftholm");
            residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
            towns.join(KING, CITIZEN, riftholm.id()).join();

            assertThat(nations.found(CITIZEN, riftholm.id(), "Valen").join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("two nations cannot share a name")
        void namesAreUnique() {
            valen();
            final Town ashford = town(MAYOR, "Ashford");

            assertThat(nations.found(MAYOR, ashford.id(), "Valen").join().denial())
                    .contains(ChangeDenial.NAME_TAKEN);
        }
    }

    @Nested
    @DisplayName("joining takes two consents")
    class Joining {

        @Test
        @DisplayName("an invitation alone changes nothing")
        void invitingIsNotJoining() {
            final Nation nation = valen();
            final TownId ashford = ashford();

            assertThat(nations.invite(KING, nation.id(), ashford).join().succeeded()).isTrue();

            assertThat(store.inTransaction(t -> t.towns().find(ashford).orElseThrow().nation())
                    .join()).isEmpty();
            assertThat(nations.invitationsFor(ashford).join()).hasSize(1);
        }

        @Test
        @DisplayName("a town cannot join a nation that has not invited it")
        void joiningNeedsAnInvitation() {
            final Nation nation = valen();
            final TownId ashford = ashford();

            assertThat(nations.accept(MAYOR, ashford, nation.id()).join().denial())
                    .as("otherwise any town could walk into a nation's territory as a citizen")
                    .contains(ChangeDenial.NO_INVITATION);
        }

        @Test
        @DisplayName("an invited town joins when its own leadership accepts")
        void acceptingJoins() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();

            final Nation joined =
                    nations.accept(MAYOR, ashford, nation.id()).join().value().orElseThrow();

            assertThat(joined.towns()).contains(ashford);
            assertThat(store.inTransaction(t -> t.towns().find(ashford).orElseThrow().nation())
                    .join()).contains(nation.id());
        }

        @Test
        @DisplayName("accepting consumes the invitation, so one offer cannot be used twice")
        void acceptingConsumesTheOffer() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();
            nations.leave(MAYOR, ashford).join();

            assertThat(nations.accept(MAYOR, ashford, nation.id()).join().denial())
                    .contains(ChangeDenial.NO_INVITATION);
        }

        @Test
        @DisplayName("someone without MANAGE_ALLEGIANCE in the town cannot accept for it")
        void acceptingNeedsTheTownsConsent() {
            final Nation nation = valen();
            final Town ashford = town(MAYOR, "Ashford");
            residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
            towns.join(MAYOR, CITIZEN, ashford.id()).join();
            nations.invite(KING, nation.id(), ashford.id()).join();

            assertThat(nations.accept(CITIZEN, ashford.id(), nation.id()).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("a lapsed invitation is refused, hidden from listings, and swept by the prune")
        void expiredInvitationsAreRefused() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();

            final NationService later = new NationService(
                    store, NamePolicy.defaults(),
                    Clock.fixed(NOW.plus(Duration.ofDays(30)), ZoneOffset.UTC),
                    town -> java.util.concurrent.CompletableFuture.completedFuture(null));

            assertThat(later.accept(MAYOR, ashford, nation.id()).join().denial())
                    .contains(ChangeDenial.INVITATION_EXPIRED);
            assertThat(later.invitationsFor(ashford).join())
                    .as("offering something that cannot be accepted reads as a bug to a player")
                    .isEmpty();
            assertThat(later.pruneExpiredInvitations().join()).isEqualTo(1);
            assertThat(later.accept(MAYOR, ashford, nation.id()).join().denial())
                    .contains(ChangeDenial.NO_INVITATION);
        }

        @Test
        @DisplayName("an offer can be withdrawn before it is taken")
        void withdrawing() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();

            assertThat(nations.withdraw(KING, nation.id(), ashford).join().succeeded()).isTrue();

            assertThat(nations.accept(MAYOR, ashford, nation.id()).join().denial())
                    .contains(ChangeDenial.NO_INVITATION);
            assertThat(nations.withdraw(KING, nation.id(), ashford).join().denial())
                    .contains(ChangeDenial.NO_INVITATION);
        }

        @Test
        @DisplayName("re-inviting refreshes the offer rather than stacking a second one")
        void reInvitingRefreshes() {
            final Nation nation = valen();
            final TownId ashford = ashford();

            nations.invite(KING, nation.id(), ashford).join();
            nations.invite(KING, nation.id(), ashford).join();

            assertThat(nations.invitationsFor(ashford).join()).hasSize(1);
        }

        @Test
        @DisplayName("a town already in a nation cannot be invited into another")
        void invitingAMemberOfAnother() {
            final Nation valen = valen();
            final Town ashford = town(MAYOR, "Ashford");
            nations.invite(KING, valen.id(), ashford.id()).join();
            nations.accept(MAYOR, ashford.id(), valen.id()).join();

            assertThat(nations.invite(KING, valen.id(), ashford.id()).join().denial())
                    .contains(ChangeDenial.TOWN_ALREADY_IN_THIS_NATION);
        }
    }

    @Nested
    @DisplayName("membership and leadership")
    class Membership {

        @Test
        @DisplayName("a member town leaves of its own accord")
        void leaving() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            assertThat(nations.leave(MAYOR, ashford).join().succeeded()).isTrue();

            assertThat(store.inTransaction(t -> t.towns().find(ashford).orElseThrow().nation())
                    .join()).isEmpty();
            assertThat(store.inTransaction(t -> t.nations().find(nation.id()).orElseThrow().towns())
                    .join()).doesNotContain(ashford);
        }

        @Test
        @DisplayName("the last town leaving dissolves the nation rather than orphaning it")
        void lastTownDissolves() {
            final Nation nation = valen();

            assertThat(nations.leave(KING, nation.capital()).join().succeeded()).isTrue();

            assertThat(store.inTransaction(t -> t.nations().find(nation.id())).join()).isEmpty();
            assertThat(store.inTransaction(t ->
                    t.roles().find(OrganisationScope.NATION, nation.id().value())).join())
                    .as("a role book for a nation that no longer exists is an orphan")
                    .isEmpty();
        }

        @Test
        @DisplayName("the capital cannot leave while other towns remain")
        void capitalMustMoveFirst() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            assertThat(nations.leave(KING, nation.capital()).join().denial())
                    .contains(ChangeDenial.CAPITAL_MUST_MOVE_FIRST);
        }

        @Test
        @DisplayName("a nation expels a member town")
        void expelling() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            assertThat(nations.expel(KING, nation.id(), ashford).join().succeeded()).isTrue();

            assertThat(store.inTransaction(t -> t.towns().find(ashford).orElseThrow().nation())
                    .join()).isEmpty();
        }

        @Test
        @DisplayName("a town's own mayor cannot expel another town from the nation")
        void expellingNeedsNationAuthority() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            assertThat(nations.expel(MAYOR, nation.id(), nation.capital()).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("the crown passes only to a citizen, and only from the sitting leader")
        void transferringTheCrown() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            assertThat(nations.transferLeadership(MAYOR, nation.id(), MAYOR).join().denial())
                    .as("a role that could hand over the crown could hand it to its own holder")
                    .contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(nations.transferLeadership(KING, nation.id(), MAYOR).join().succeeded())
                    .isTrue();
        }

        @Test
        @DisplayName("the crown does not pass to someone in no member town")
        void crownNeedsCitizenship() {
            final Nation nation = valen();
            residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();

            assertThat(nations.transferLeadership(KING, nation.id(), CITIZEN).join().denial())
                    .contains(ChangeDenial.LEADER_MUST_BE_A_MEMBER);
        }

        @Test
        @DisplayName("the capital moves to another member town")
        void movingTheCapital() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            assertThat(nations.moveCapital(KING, nation.id(), ashford).join()
                    .value().orElseThrow().capital()).isEqualTo(ashford);
        }
    }

    @Nested
    @DisplayName("the civic cache follows")
    class CacheFollows {

        @Test
        @DisplayName("joining a nation reaches the cache, so citizens are recognised in its towns")
        void joiningRefreshesTheCache() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            assertThat(civicCache.nationOf(ashford))
                    .as("a stale cache would keep treating this town's residents as outsiders")
                    .contains(nation.id());
            assertThat(civicCache.town(ashford).map(TownFacts::displayName)).contains("Ashford");
        }

        @Test
        @DisplayName("leaving reaches it too, so the relationship stops applying")
        void leavingRefreshesTheCache() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            nations.leave(MAYOR, ashford).join();

            assertThat(civicCache.nationOf(ashford)).isEmpty();
        }

        @Test
        @DisplayName("disbanding releases every member town in the cache")
        void disbandRefreshesEveryTown() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            assertThat(nations.disband(KING, nation.id()).join().succeeded()).isTrue();

            assertThat(civicCache.nationOf(ashford)).isEmpty();
            assertThat(civicCache.nationOf(nation.capital())).isEmpty();
            assertThat(store.inTransaction(t -> t.nations().find(nation.id())).join()).isEmpty();
        }

        @Test
        @DisplayName("disbanding withdraws the offers the nation had outstanding")
        void disbandSweepsInvitations() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();

            nations.disband(KING, nation.id()).join();

            assertThat(nations.invitationsFor(ashford).join()).isEmpty();
        }
    }

    @Nested
    @DisplayName("nation roles")
    class NationRoles {

        @Test
        @DisplayName("a citizen holds the member defaults and so cannot invite")
        void citizensHoldMemberDefaults() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            // MANAGE_ALLEGIANCE is not a member default, so a citizen of a member town cannot
            // invite on the nation's behalf even though they are a mayor in their own town.
            final TownId third = town(CITIZEN, "Highholm").id();
            assertThat(nations.invite(MAYOR, nation.id(), third).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("a nation role can be granted the permission, and then it works")
        void nationRolesCanBeGranted() {
            final Nation nation = valen();
            final TownId ashford = ashford();
            nations.invite(KING, nation.id(), ashford).join();
            nations.accept(MAYOR, ashford, nation.id()).join();

            // No NationRoleService yet, so the book is edited directly. The point being asserted is
            // that NationService reads it, not that a command exists to do this.
            grantNationRole(nation, MAYOR, Permission.MANAGE_ALLEGIANCE);

            final TownId third = town(CITIZEN, "Highholm").id();
            assertThat(nations.invite(MAYOR, nation.id(), third).join().succeeded()).isTrue();
        }

        @Test
        @DisplayName("a resident of no member town is a visitor to the nation")
        void outsidersAreVisitors() {
            final Nation nation = valen();
            final TownId ashford = ashford();

            assertThat(nations.invite(MAYOR, nation.id(), ashford).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }
    }

    /** Gives a citizen a nation role carrying one permission, by writing the book directly. */
    private void grantNationRole(
            final Nation nation, final ResidentId who, final Permission permission) {
        store.inTransaction(transaction -> {
            final RoleBook book = transaction.roles()
                    .find(OrganisationScope.NATION, nation.id().value()).orElseThrow();
            final net.riftbreaker.rifttowny.domain.role.Role role =
                    net.riftbreaker.rifttowny.domain.role.Role.custom(
                            RoleId.random(), OrganisationScope.NATION, nation.id().value(),
                            "Envoy", SystemRole.MEMBER.priority() + 10, Set.of(permission), NOW);
            final RoleBook created = book.create(role, Set.of()).orElseThrow();
            transaction.roles().save(created.assign(who, role.id()).orElseThrow());
            return null;
        }).join();
    }
}
