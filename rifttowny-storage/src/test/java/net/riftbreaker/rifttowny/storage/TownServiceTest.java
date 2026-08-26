package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.NameProblem;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.bank.LedgerEntry;
import net.riftbreaker.rifttowny.domain.bank.Money;
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
    private final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index =
            net.riftbreaker.rifttowny.domain.territory.TerritoryIndex.empty();
    private JdbcCivicStore store;
    private JdbcTownRepository towns;
    private JdbcResidentRepository residents;
    private JdbcOutboxRepository outbox;

    @BeforeEach
    void createService() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        service = new TownService(store, NamePolicy.defaults(), CLOCK, index);
        towns = new JdbcTownRepository(database, DIRECT);
        residents = new JdbcResidentRepository(database, DIRECT);
        outbox = new JdbcOutboxRepository(database, DIRECT);
    }

    private Town foundRiftholm() {
        return service.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
    }

    private static final java.util.UUID WORLD = java.util.UUID.randomUUID();

    /** Land straight into the store and the index, since the merge is what is under test. */
    private void giveLand(final net.riftbreaker.rifttowny.domain.org.TownId town, final ChunkKey... chunks) {
        for (final ChunkKey chunk : chunks) {
            final var claim = net.riftbreaker.rifttowny.domain.territory.Claim.of(
                    chunk, town, net.riftbreaker.rifttowny.domain.territory.ClaimKind.ORDINARY,
                    CLOCK.instant());
            store.inTransaction(t -> {
                t.claims().insert(claim);
                return null;
            }).join();
            index.put(claim);
        }
    }

    private static Money money(final String amount) {
        return Money.of(new java.math.BigDecimal(amount), "coins");
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
        @DisplayName("leaving strips every role, so a departed officer stops governing the town")
        void leavingRevokesRoles() {
            final Town town = foundRiftholm();
            seeAsNewcomer(OFFICER, "Officer");
            service.join(MAYOR, OFFICER, town.id()).join();
            giveRole(town.id(), OFFICER, 500, Permission.KICK_RESIDENT, Permission.DISBAND);
            assertThat(service.permissionsOf(OFFICER, town.id()).join())
                    .contains(Permission.DISBAND);

            service.leave(OFFICER, town.id()).join();

            assertThat(service.permissionsOf(OFFICER, town.id()).join())
                    .as("role assignments do not expire with residency unless something revokes them")
                    .doesNotContain(Permission.DISBAND, Permission.KICK_RESIDENT);
            assertThat(service.disband(OFFICER, town.id()).join().denial())
                    .as("an ex-resident must not still be able to destroy the town")
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("a kick strips the victim's roles too, so removal actually removes authority")
        void kickRevokesRoles() {
            final Town town = foundRiftholm();
            seeAsNewcomer(OFFICER, "Officer");
            service.join(MAYOR, OFFICER, town.id()).join();
            giveRole(town.id(), OFFICER, 500, Permission.DISBAND);

            service.kick(MAYOR, OFFICER, town.id()).join();

            assertThat(service.permissionsOf(OFFICER, town.id()).join())
                    .doesNotContain(Permission.DISBAND);
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

    /**
     * Trust, through real storage.
     *
     * <p>The aggregate's own rules are covered where they live. What only a database shows is that
     * the set survives the round trip at all — trust is written by {@code ConnectionTownStore} as a
     * delete-and-reinsert of the whole list beside the town row, which is the kind of write that
     * silently loses everything if the town is saved by a path that did not load it.</p>
     */
    @Nested
    @DisplayName("trusting an outsider")
    class Trusting {

        private Town riftholm;

        @BeforeEach
        void found() {
            seeAsNewcomer(MAYOR, "Mayor");
            seeAsNewcomer(OFFICER, "Officer");
            seeAsNewcomer(CITIZEN, "Citizen");
            riftholm = foundRiftholm();
        }

        @Test
        @DisplayName("is written, survives a reload, and lifts them off the visitor rung")
        void survivesAReload() {
            assertThat(service.trust(MAYOR, riftholm.id(), OFFICER).join().succeeded()).isTrue();

            final Town loaded = towns.find(riftholm.id()).join().orElseThrow();
            assertThat(loaded.trustedOutsiders()).containsExactly(OFFICER);
            // The point of the whole thing: this is what the protection ladder reads.
            assertThat(net.riftbreaker.rifttowny.domain.civic.TownFacts
                    .of(loaded, net.riftbreaker.rifttowny.domain.role.RoleBook.defaultsFor(
                            net.riftbreaker.rifttowny.domain.org.OrganisationScope.TOWN,
                            riftholm.id().value(), CLOCK.instant()))
                    .trusts(OFFICER)).isTrue();
        }

        @Test
        @DisplayName("revoking it takes them straight back off")
        void revoking() {
            service.trust(MAYOR, riftholm.id(), OFFICER).join();

            assertThat(service.untrust(MAYOR, riftholm.id(), OFFICER).join().succeeded()).isTrue();
            assertThat(towns.find(riftholm.id()).join().orElseThrow().trustedOutsiders()).isEmpty();
        }

        @Test
        @DisplayName("a resident cannot be trusted, because they already outrank it")
        void residentsCannotBeTrusted() {
            service.join(MAYOR, CITIZEN, riftholm.id()).join();

            assertThat(service.trust(MAYOR, riftholm.id(), CITIZEN).join().denial())
                    .contains(ChangeDenial.CANNOT_TRUST_A_RESIDENT);
        }

        @Test
        @DisplayName("admitting a trusted outsider clears their trust, so nobody holds both")
        void admissionClearsTrust() {
            // The aggregate does this; what this proves is that it reaches storage. A member still
            // carrying outsider trust would be the overlap CANNOT_TRUST_A_RESIDENT exists to stop.
            service.trust(MAYOR, riftholm.id(), OFFICER).join();

            service.join(MAYOR, OFFICER, riftholm.id()).join();

            final Town loaded = towns.find(riftholm.id()).join().orElseThrow();
            assertThat(loaded.residents()).contains(OFFICER);
            assertThat(loaded.trustedOutsiders()).isEmpty();
        }

        @Test
        @DisplayName("twice is refused, and so is revoking what was never granted")
        void refusals() {
            service.trust(MAYOR, riftholm.id(), OFFICER).join();

            assertThat(service.trust(MAYOR, riftholm.id(), OFFICER).join().denial())
                    .contains(ChangeDenial.ALREADY_TRUSTED);
            assertThat(service.untrust(MAYOR, riftholm.id(), CITIZEN).join().denial())
                    .contains(ChangeDenial.NOT_TRUSTED);
        }

        @Test
        @DisplayName("somebody with no standing in the town cannot grant it")
        void needsThePermission() {
            final var refused = service.trust(OFFICER, riftholm.id(), CITIZEN).join();

            assertThat(refused.denial()).contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(towns.find(riftholm.id()).join().orElseThrow().trustedOutsiders()).isEmpty();
        }
    }

    /**
     * Merging two towns.
     *
     * <p>The hazards here are ordering hazards, and they are silent. {@code rt_claim} cascades from
     * {@code rt_town} and {@code ConnectionTownStore.delete} nulls {@code rt_resident.town_id}, so a
     * merge written in the wrong order destroys the land it was moving and strands the people it was
     * moving them for — with no exception, no refusal, and a success message. Every test below is
     * really a test of the order.</p>
     */
    @Nested
    @DisplayName("merging")
    class Merging {

        private Town riftholm;
        private Town ashford;

        @BeforeEach
        void twoTowns() {
            seeAsNewcomer(MAYOR, "Mayor");
            seeAsNewcomer(OFFICER, "Officer");
            seeAsNewcomer(CITIZEN, "Citizen");
            riftholm = foundRiftholm();
            ashford = service.found(OFFICER, "Officer", "Ashford").join().value().orElseThrow();
            service.join(OFFICER, CITIZEN, ashford.id()).join();
        }

        private void offerAndAccept() {
            service.offerMerge(MAYOR, riftholm.id(), ashford.id()).join();
            final var merged = service.acceptMerge(OFFICER, ashford.id(), riftholm.id()).join();
            assertThat(merged.succeeded()).as("%s", merged.denial()).isTrue();
        }

        @Test
        @DisplayName("the absorbed town's people become the survivor's, and keep a town")
        void residentsMove() {
            offerAndAccept();

            final Town survivor = towns.find(riftholm.id()).join().orElseThrow();
            assertThat(survivor.residents()).contains(MAYOR, OFFICER, CITIZEN);
            assertThat(towns.find(ashford.id()).join()).isEmpty();
            // The trap: ConnectionTownStore.delete nulls rt_resident.town_id for the town it drops.
            // Move the people after the delete and they end up in no town at all.
            assertThat(residents.find(CITIZEN).join().orElseThrow().town()).contains(riftholm.id());
            assertThat(residents.find(OFFICER).join().orElseThrow().town()).contains(riftholm.id());
        }

        @Test
        @DisplayName("the absorbed town's land changes hands rather than being destroyed")
        void landMoves() {
            // The worst failure this feature can have, and it is silent: rt_claim cascades from
            // rt_town, so deleting the town before moving its chunks deletes the chunks.
            giveLand(ashford.id(), new ChunkKey(WORLD, 40, 40), new ChunkKey(WORLD, 41, 40));
            final int before = store.inTransaction(t -> t.claims().of(riftholm.id()).size()).join();

            offerAndAccept();

            final int after = store.inTransaction(t -> t.claims().of(riftholm.id()).size()).join();
            assertThat(after).isEqualTo(before + 2);
            assertThat(store.inTransaction(t -> t.claims().of(ashford.id())).join()).isEmpty();
            // And the in-memory index agrees, or protection would answer for a town that is gone.
            assertThat(index.ownerOf(new ChunkKey(WORLD, 40, 40))).contains(riftholm.id());
        }

        @Test
        @DisplayName("the treasury moves, and both ledgers say where it went")
        void moneyMoves() {
            final var bank = new net.riftbreaker.rifttowny.domain.service.BankService(
                    store, CLOCK, net.riftbreaker.rifttowny.domain.bank.PlayerWallet.absent());
            bank.pay(ashford.id(), money("60"), LedgerEntry.Reason.TAX, null).join();

            offerAndAccept();

            assertThat(bank.balanceOf(riftholm.id()).join()).isEqualTo(money("60"));
            // The absorbed account's history survives on purpose: BankStore.forget would delete the
            // ledger with the balance, and a merge is exactly when somebody asks where money went.
            final var absorbedLedger = store.inTransaction(
                    t -> t.bank().history(ashford.bankAccountId(), 10)).join();
            assertThat(absorbedLedger).isNotEmpty();
            assertThat(absorbedLedger.getFirst().note()).contains("merged into Riftholm");
        }

        @Test
        @DisplayName("an outlawry against somebody being absorbed is lifted, not left standing")
        void outlawriesAreLifted() {
            // Otherwise the survivor ends the merge with a member its own town has outlawed, which
            // is the state OutlawService refuses outright.
            store.inTransaction(t -> {
                t.outlaws().declare(riftholm.id(), CITIZEN, MAYOR, CLOCK.instant());
                return null;
            }).join();

            offerAndAccept();

            assertThat(store.inTransaction(t -> t.outlaws().holds(riftholm.id(), CITIZEN)).join())
                    .isFalse();
        }

        @Test
        @DisplayName("what the absorbed town alone owned goes with it")
        void absorbedBelongingsGo() {
            offerAndAccept();

            assertThat(store.inTransaction(t -> t.spawns().of(ashford.id())).join()).isEmpty();
            assertThat(store.inTransaction(t -> t.roles().find(
                    OrganisationScope.TOWN, ashford.id().value())).join()).isEmpty();
        }

        @Test
        @DisplayName("only the two mayors can, on their own sides")
        void mayorsOnly() {
            assertThat(service.offerMerge(CITIZEN, riftholm.id(), ashford.id()).join().denial())
                    .as("a non-mayor offering")
                    .contains(ChangeDenial.MISSING_PERMISSION);

            service.offerMerge(MAYOR, riftholm.id(), ashford.id()).join();
            assertThat(service.acceptMerge(CITIZEN, ashford.id(), riftholm.id()).join().denial())
                    .as("a member accepting on their mayor's behalf")
                    .contains(ChangeDenial.MISSING_PERMISSION);
            // Refused and unchanged: both towns still stand.
            assertThat(towns.find(ashford.id()).join()).isPresent();
        }

        @Test
        @DisplayName("accepting without a standing offer is refused")
        void needsAnOffer() {
            assertThat(service.acceptMerge(OFFICER, ashford.id(), riftholm.id()).join().denial())
                    .contains(ChangeDenial.NO_INVITATION);
            assertThat(towns.find(ashford.id()).join()).isPresent();
        }

        @Test
        @DisplayName("a town cannot merge with itself")
        void notWithItself() {
            assertThat(service.offerMerge(MAYOR, riftholm.id(), riftholm.id()).join().denial())
                    .contains(ChangeDenial.CANNOT_MERGE_WITH_SELF);
        }

        @Test
        @DisplayName("two towns in different nations are refused, and nothing is touched")
        void nationsMustMatch() {
            // Allowing it would either enrol a townful of strangers in a nation that never invited
            // them, or take a member town out of one on two town mayors' word.
            final var nations = new net.riftbreaker.rifttowny.domain.service.NationService(
                    store, NamePolicy.defaults(), CLOCK,
                    net.riftbreaker.rifttowny.domain.service.CivicCacheRefresher.none());
            nations.found(MAYOR, riftholm.id(), "Valen").join();

            service.offerMerge(MAYOR, riftholm.id(), ashford.id()).join();
            final var refused = service.acceptMerge(OFFICER, ashford.id(), riftholm.id()).join();

            assertThat(refused.denial()).contains(ChangeDenial.MERGE_REQUIRES_THE_SAME_NATION);
            assertThat(towns.find(ashford.id()).join()).isPresent();
            assertThat(residents.find(CITIZEN).join().orElseThrow().town()).contains(ashford.id());
        }
    }

    /**
     * Purging residents nobody has seen.
     *
     * <p>Every test here is really about an exclusion. The removal itself is the kick path repeated,
     * which is covered above; what is specific to a purge is who it must refuse to touch, because
     * each of those is a way to wreck a town with one number.</p>
     */
    @Nested
    @DisplayName("purging the inactive")
    class Purging {

        private Town riftholm;

        @BeforeEach
        void aTownWithStragglers() {
            seeAsNewcomer(MAYOR, "Mayor");
            seeAsNewcomer(OFFICER, "Officer");
            seeAsNewcomer(CITIZEN, "Citizen");
            riftholm = foundRiftholm();
            service.join(MAYOR, OFFICER, riftholm.id()).join();
            service.join(MAYOR, CITIZEN, riftholm.id()).join();
        }

        /** Backdates somebody's last login, which is the only thing a purge reads. */
        private void lastSeen(final ResidentId who, final java.time.Duration ago) {
            final Resident resident = residents.find(who).join().orElseThrow();
            residents.save(resident.seenAt(CLOCK.instant().minus(ago))).join();
        }

        @Test
        @DisplayName("removes somebody past the cutoff and leaves somebody inside it")
        void removesTheInactive() {
            lastSeen(CITIZEN, java.time.Duration.ofDays(40));
            lastSeen(OFFICER, java.time.Duration.ofDays(3));

            final var purged = service.purge(
                    MAYOR, riftholm.id(), java.time.Duration.ofDays(30), true).join();

            assertThat(purged.succeeded()).as("%s", purged.denial()).isTrue();
            assertThat(purged.value().orElseThrow().removed()).containsExactly(CITIZEN);
            final Town after = towns.find(riftholm.id()).join().orElseThrow();
            assertThat(after.residents()).containsExactlyInAnyOrder(MAYOR, OFFICER);
            assertThat(residents.find(CITIZEN).join().orElseThrow().town()).isEmpty();
        }

        @Test
        @DisplayName("a preview changes nothing, and reports what the real one would do")
        void previewChangesNothing() {
            // The importer previewed "0 towns" and then imported forty, because the counting lived
            // in the writing pass. Here both numbers come from the same pass by construction.
            lastSeen(CITIZEN, java.time.Duration.ofDays(40));

            final var preview = service.purge(
                    MAYOR, riftholm.id(), java.time.Duration.ofDays(30), false).join();

            assertThat(preview.value().orElseThrow().applied()).isFalse();
            assertThat(preview.value().orElseThrow().removed()).containsExactly(CITIZEN);
            assertThat(towns.find(riftholm.id()).join().orElseThrow().residents())
                    .containsExactlyInAnyOrder(MAYOR, OFFICER, CITIZEN);

            final var real = service.purge(
                    MAYOR, riftholm.id(), java.time.Duration.ofDays(30), true).join();
            assertThat(real.value().orElseThrow().removed())
                    .as("the preview and the purge must agree")
                    .isEqualTo(preview.value().orElseThrow().removed());
        }

        @Test
        @DisplayName("the mayor is never purged, however long they have been away")
        void theMayorSurvives() {
            // Town.release refuses the mayor anyway, but hitting that inside the loop would roll
            // back the whole purge - and a mayor tidying up their own town while inactive
            // themselves is an ordinary way to use this.
            lastSeen(MAYOR, java.time.Duration.ofDays(400));
            lastSeen(CITIZEN, java.time.Duration.ofDays(40));

            final var purged = service.purge(
                    MAYOR, riftholm.id(), java.time.Duration.ofDays(30), true).join();

            assertThat(purged.value().orElseThrow().removed()).containsExactly(CITIZEN);
            assertThat(towns.find(riftholm.id()).join().orElseThrow().residents()).contains(MAYOR);
        }

        @Test
        @DisplayName("somebody the actor does not outrank is skipped and counted")
        void outrankedAreSkipped() {
            // Without this an officer could clear out their co-officers by choosing a number that
            // happens to catch them. Skipped rather than refused, so one protected resident does
            // not make the command useless on the town that most needs it.
            final ResidentId junior = ResidentId.of(java.util.UUID.randomUUID());
            seeAsNewcomer(junior, "Junior");
            service.join(MAYOR, junior, riftholm.id()).join();
            lastSeen(CITIZEN, java.time.Duration.ofDays(40));
            lastSeen(junior, java.time.Duration.ofDays(40));
            // The officer may kick, but CITIZEN outranks them and junior does not.
            giveRole(riftholm.id(), OFFICER, 500, Permission.KICK_RESIDENT);
            giveRole(riftholm.id(), CITIZEN, 600);

            final var purged = service.purge(
                    OFFICER, riftholm.id(), java.time.Duration.ofDays(30), true).join();

            assertThat(purged.succeeded()).as("%s", purged.denial()).isTrue();
            assertThat(purged.value().orElseThrow().removed()).containsExactly(junior);
            assertThat(purged.value().orElseThrow().protectedByRank()).isEqualTo(1);
            assertThat(towns.find(riftholm.id()).join().orElseThrow().residents())
                    .contains(CITIZEN);
        }

        @Test
        @DisplayName("a purge that catches nobody is not a failure")
        void nobodyToPurge() {
            final var purged = service.purge(
                    MAYOR, riftholm.id(), java.time.Duration.ofDays(30), true).join();

            assertThat(purged.succeeded()).isTrue();
            assertThat(purged.value().orElseThrow().count()).isZero();
        }

        @Test
        @DisplayName("somebody without the permission cannot purge at all")
        void needsThePermission() {
            lastSeen(CITIZEN, java.time.Duration.ofDays(40));

            final var refused = service.purge(
                    CITIZEN, riftholm.id(), java.time.Duration.ofDays(30), true).join();

            assertThat(refused.denial()).contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(towns.find(riftholm.id()).join().orElseThrow().residents()).contains(CITIZEN);
        }

        @Test
        @DisplayName("a purged resident's plots go back to the town")
        void plotsAreReleased() {
            // The same rule a kick applies: a plot is authority over a square inside the town, and
            // somebody who is no longer a member should not keep it.
            lastSeen(CITIZEN, java.time.Duration.ofDays(40));
            final var chunk = new ChunkKey(WORLD, 7, 7);
            store.inTransaction(t -> {
                t.claims().insert(net.riftbreaker.rifttowny.domain.territory.Claim.of(
                        chunk, riftholm.id(),
                        net.riftbreaker.rifttowny.domain.territory.ClaimKind.ORDINARY,
                        CLOCK.instant()));
                return null;
            }).join();
            store.inTransaction(t -> {
                final var claim = t.claims().at(chunk).orElseThrow();
                t.claims().updatePlot(new net.riftbreaker.rifttowny.domain.territory.Claim(
                        claim.id(), claim.chunk(), claim.town(), claim.kind(), claim.type(),
                        CITIZEN, claim.claimedAt()));
                return null;
            }).join();

            service.purge(MAYOR, riftholm.id(), java.time.Duration.ofDays(30), true).join();

            assertThat(store.inTransaction(t -> t.claims().at(chunk)).join().orElseThrow().owner())
                    .isNull();
        }
    }
}
