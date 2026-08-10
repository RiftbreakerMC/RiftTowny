package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.NameProblem;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.service.ServiceResult;
import net.riftbreaker.rifttowny.domain.service.TownService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private static final ResidentId ALDER = ResidentId.of(UUID.randomUUID());
    private static final ResidentId BRIAR = ResidentId.of(UUID.randomUUID());

    private TownService service;
    private JdbcTownRepository towns;
    private JdbcResidentRepository residents;
    private JdbcOutboxRepository outbox;

    @BeforeEach
    void createService() {
        service = new TownService(
                new JdbcCivicStore(database, DIRECT, CLOCK), NamePolicy.defaults(), CLOCK);
        towns = new JdbcTownRepository(database, DIRECT);
        residents = new JdbcResidentRepository(database, DIRECT);
        outbox = new JdbcOutboxRepository(database, DIRECT);
    }

    private Town foundRiftholm() {
        return service.found(ALDER, "Alder", "Riftholm").join().value().orElseThrow();
    }

    @Test
    @DisplayName("founding creates the town, the resident and the announcement together")
    void foundingCommitsEverything() {
        final Town town = foundRiftholm();

        assertThat(town.mayor()).isEqualTo(ALDER);
        assertThat(towns.find(town.id()).join().orElseThrow().residents()).containsExactly(ALDER);
        assertThat(residents.find(ALDER).join().orElseThrow().town()).contains(town.id());
        assertThat(outbox.counts().join().pending()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a bad name is rejected with every problem, and nothing is written")
    void badNameWritesNothing() {
        final ServiceResult<Town> result = service.found(ALDER, "Alder", "1 !").join();

        assertThat(result.nameProblems())
                .contains(NameProblem.MUST_START_WITH_LETTER, NameProblem.CONTAINS_WHITESPACE);
        assertThat(towns.count().join()).isZero();
        assertThat(residents.find(ALDER).join()).isEmpty();
        assertThat(outbox.counts().join().total()).isZero();
    }

    @Test
    @DisplayName("a taken name is refused and leaves the founder townless")
    void takenNameRollsBack() {
        foundRiftholm();

        final ServiceResult<Town> result = service.found(BRIAR, "Briar", "riftholm").join();

        assertThat(result.denial()).contains(ChangeDenial.NAME_TAKEN);
        assertThat(towns.count().join()).isEqualTo(1);
        assertThat(residents.find(BRIAR).join())
                .as("a refused founding must not leave a resident row behind")
                .isEmpty();
        assertThat(outbox.counts().join().total()).isEqualTo(1L);
    }

    @Test
    @DisplayName("someone already in a town cannot found another")
    void oneTownPerResidentIsEnforcedByTheService() {
        foundRiftholm();

        final ServiceResult<Town> result = service.found(ALDER, "Alder", "Ashford").join();

        assertThat(result.denial()).contains(ChangeDenial.ALREADY_IN_ANOTHER_TOWN);
        assertThat(towns.count().join()).isEqualTo(1);
    }

    @Test
    @DisplayName("joining updates both sides in one transaction")
    void joinUpdatesBothSides() {
        final Town town = foundRiftholm();
        residents.save(net.riftbreaker.rifttowny.domain.org.Resident
                .newcomer(BRIAR, "Briar", CLOCK.instant())).join();

        final ServiceResult<Town> result = service.join(BRIAR, town.id()).join();

        assertThat(result.succeeded()).isTrue();
        // In any order: this test runs on a fixed clock, so both residents share a joined_at and the
        // ordering falls back to the resident_id tiebreak, which is stable but arbitrary. Join order
        // is asserted in JdbcResidentRepositoryTest, where the timestamps differ.
        assertThat(towns.find(town.id()).join().orElseThrow().residents())
                .containsExactlyInAnyOrder(ALDER, BRIAR);
        assertThat(residents.find(BRIAR).join().orElseThrow().town()).contains(town.id());
    }

    @Test
    @DisplayName("joining a town that does not exist is refused, not a crash")
    void joinUnknownTown() {
        assertThat(service.join(BRIAR, TownId.random()).join().denial())
                .contains(ChangeDenial.TOWN_NOT_FOUND);
    }

    @Test
    @DisplayName("a player RiftTowny has never seen cannot join")
    void joinUnknownResident() {
        final Town town = foundRiftholm();

        assertThat(service.join(BRIAR, town.id()).join().denial())
                .contains(ChangeDenial.RESIDENT_NOT_FOUND);
    }

    @Test
    @DisplayName("leaving updates both sides")
    void leaveUpdatesBothSides() {
        final Town town = foundRiftholm();
        residents.save(net.riftbreaker.rifttowny.domain.org.Resident
                .newcomer(BRIAR, "Briar", CLOCK.instant())).join();
        service.join(BRIAR, town.id()).join();

        assertThat(service.leave(BRIAR, town.id(), true).join().succeeded()).isTrue();

        assertThat(towns.find(town.id()).join().orElseThrow().residents()).containsExactly(ALDER);
        assertThat(residents.find(BRIAR).join().orElseThrow().town()).isEmpty();
    }

    @Test
    @DisplayName("the mayor cannot leave while others remain, and nothing changes")
    void mayorCannotLeave() {
        final Town town = foundRiftholm();
        residents.save(net.riftbreaker.rifttowny.domain.org.Resident
                .newcomer(BRIAR, "Briar", CLOCK.instant())).join();
        service.join(BRIAR, town.id()).join();

        assertThat(service.leave(ALDER, town.id(), true).join().denial())
                .contains(ChangeDenial.MAYOR_MUST_TRANSFER_FIRST);
        assertThat(residents.find(ALDER).join().orElseThrow().town())
                .as("the refused departure must not have cleared the resident's town")
                .contains(town.id());
    }

    @Test
    @DisplayName("the last resident is told to disband instead of leaving")
    void lastResidentIsToldToDisband() {
        final Town town = foundRiftholm();

        assertThat(service.leave(ALDER, town.id(), true).join().denial())
                .contains(ChangeDenial.LAST_RESIDENT_MUST_DISBAND_INSTEAD);
    }

    @Test
    @DisplayName("the mayoralty transfers to a resident")
    void mayoraltyTransfers() {
        final Town town = foundRiftholm();
        residents.save(net.riftbreaker.rifttowny.domain.org.Resident
                .newcomer(BRIAR, "Briar", CLOCK.instant())).join();
        service.join(BRIAR, town.id()).join();

        assertThat(service.transferMayoralty(town.id(), BRIAR).join().succeeded()).isTrue();

        final Town loaded = towns.find(town.id()).join().orElseThrow();
        assertThat(loaded.mayor()).isEqualTo(BRIAR);
        assertThat(loaded.bankAccountId()).isEqualTo(town.bankAccountId());
    }

    @Test
    @DisplayName("renaming keeps the id and the civic account")
    void renameKeepsIdentity() {
        final Town town = foundRiftholm();

        assertThat(service.rename(town.id(), "Ashford").join().succeeded()).isTrue();

        final Town loaded = towns.find(town.id()).join().orElseThrow();
        assertThat(loaded.name().display()).isEqualTo("Ashford");
        assertThat(loaded.bankAccountId()).isEqualTo(town.bankAccountId());
    }

    @Test
    @DisplayName("a town may recapitalise its own name without colliding with itself")
    void recapitalisingOwnNameIsAllowed() {
        final Town town = foundRiftholm();

        assertThat(service.rename(town.id(), "RIFTHOLM").join().succeeded()).isTrue();
        assertThat(towns.find(town.id()).join().orElseThrow().name().display()).isEqualTo("RIFTHOLM");
    }

    @Test
    @DisplayName("renaming onto another town's name is refused")
    void renameOntoAnotherTownIsRefused() {
        final Town riftholm = foundRiftholm();
        residents.save(net.riftbreaker.rifttowny.domain.org.Resident
                .newcomer(BRIAR, "Briar", CLOCK.instant())).join();
        service.found(BRIAR, "Briar", "Ashford").join();

        assertThat(service.rename(riftholm.id(), "Ashford").join().denial())
                .contains(ChangeDenial.NAME_TAKEN);
        assertThat(towns.find(riftholm.id()).join().orElseThrow().name().display())
                .isEqualTo("Riftholm");
    }

    @Test
    @DisplayName("disbanding removes the town and releases its residents without deleting them")
    void disbandReleasesResidents() {
        final Town town = foundRiftholm();
        residents.save(net.riftbreaker.rifttowny.domain.org.Resident
                .newcomer(BRIAR, "Briar", CLOCK.instant())).join();
        service.join(BRIAR, town.id()).join();

        assertThat(service.disband(town.id()).join().succeeded()).isTrue();

        assertThat(towns.find(town.id()).join()).isEmpty();
        assertThat(residents.find(ALDER).join().orElseThrow().town()).isEmpty();
        assertThat(residents.find(BRIAR).join().orElseThrow().town()).isEmpty();
    }

    @Test
    @DisplayName("disbanding announces how many residents it released")
    void disbandAnnouncesTheHeadcount() {
        final Town town = foundRiftholm();
        final long before = outbox.counts().join().total();

        service.disband(town.id()).join();

        assertThat(outbox.counts().join().total()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("disbanding a town that does not exist is refused")
    void disbandUnknownTown() {
        assertThat(service.disband(TownId.random()).join().denial())
                .contains(ChangeDenial.TOWN_NOT_FOUND);
    }
}
