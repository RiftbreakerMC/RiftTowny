package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTownRepositoryTest extends SqliteFixture {

    private static final Instant DAY_ONE = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant DAY_TWO = Instant.parse("2026-08-02T10:00:00Z");

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());
    private static final ResidentId OUTSIDER = ResidentId.of(UUID.randomUUID());

    private JdbcTownRepository towns;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createRepositories() {
        towns = new JdbcTownRepository(database, DIRECT);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private static OrganisationName name(final String raw) {
        return NamePolicy.defaults().check(raw).accepted().orElseThrow();
    }

    /**
     * Founds a town the way a service must: the founder's resident row first, because
     * {@code rt_resident.town_id} is what a town's membership is rebuilt from.
     */
    private Town foundRiftholm() {
        final Town town = Town.found(
                TownId.random(), name("Riftholm"), MAYOR, UUID.randomUUID(), DAY_ONE);
        residents.save(Resident.newcomer(MAYOR, "Mayor", DAY_ONE)
                .joinTown(town.id()).orElseThrow()).join();
        towns.save(town).join();
        return town;
    }

    @Test
    @DisplayName("a saved town round-trips with its identity, account and membership")
    void saveAndFind() {
        final Town town = foundRiftholm();

        final Town loaded = towns.find(town.id()).join().orElseThrow();

        assertThat(loaded.id()).isEqualTo(town.id());
        assertThat(loaded.name().display()).isEqualTo("Riftholm");
        assertThat(loaded.mayor()).isEqualTo(MAYOR);
        assertThat(loaded.bankAccountId()).isEqualTo(town.bankAccountId());
        assertThat(loaded.residents()).containsExactly(MAYOR);
        assertThat(loaded.nation()).isEmpty();
        assertThat(loaded.createdAt()).isEqualTo(DAY_ONE);
    }

    @Test
    @DisplayName("an unknown town is empty, not an error")
    void unknownTownIsEmpty() {
        assertThat(towns.find(TownId.random()).join()).isEmpty();
    }

    @Test
    @DisplayName("lookup by name uses the normalised column, so it agrees with the unique constraint")
    void findByNameIsCaseInsensitive() {
        foundRiftholm();

        assertThat(towns.findByName("riftholm").join()).isPresent();
        assertThat(towns.findByName("RIFTHOLM").join()).isPresent();
        assertThat(towns.findByName("Ashford").join()).isEmpty();
    }

    @Test
    @DisplayName("renaming keeps the id and the bank account, which is the whole point")
    void renameKeepsIdentityAndAccount() {
        final Town town = foundRiftholm();

        towns.save(town.renameTo(name("Ashford")).orElseThrow()).join();

        final Town loaded = towns.find(town.id()).join().orElseThrow();
        assertThat(loaded.name().display()).isEqualTo("Ashford");
        assertThat(loaded.bankAccountId()).isEqualTo(town.bankAccountId());
        assertThat(towns.findByName("riftholm").join()).isEmpty();
        assertThat(towns.count().join()).isEqualTo(1);
    }

    @Test
    @DisplayName("a leadership transfer is persisted without moving the bank account")
    void leadershipTransferPersists() {
        final Town town = foundRiftholm();
        residents.save(Resident.newcomer(CITIZEN, "Citizen", DAY_TWO)
                .joinTown(town.id()).orElseThrow()).join();
        final Town withCitizen = towns.find(town.id()).join().orElseThrow();

        towns.save(withCitizen.transferLeadership(CITIZEN).orElseThrow()).join();

        final Town loaded = towns.find(town.id()).join().orElseThrow();
        assertThat(loaded.mayor()).isEqualTo(CITIZEN);
        assertThat(loaded.bankAccountId()).isEqualTo(town.bankAccountId());
    }

    @Test
    @DisplayName("membership comes from the resident table, so saving a town cannot invent members")
    void membershipIsOwnedByTheResidentTable() {
        final Town town = foundRiftholm();

        // A town aggregate that believes it has a second resident, saved without that resident's
        // own row ever being written.
        final Town optimistic = town.admit(CITIZEN).orElseThrow();
        towns.save(optimistic).join();

        assertThat(towns.find(town.id()).join().orElseThrow().residents())
                .as("the town row cannot conjure a member that rt_resident does not list")
                .containsExactly(MAYOR);
    }

    @Test
    @DisplayName("residents come back in join order across a reload")
    void residentOrderSurvivesAReload() {
        final Town town = foundRiftholm();
        residents.save(Resident.newcomer(CITIZEN, "Citizen", DAY_TWO)
                .joinTown(town.id()).orElseThrow()).join();

        assertThat(towns.find(town.id()).join().orElseThrow().residents())
                .containsExactly(MAYOR, CITIZEN);
    }

    @Test
    @DisplayName("trusted outsiders round-trip and are reconciled, not accumulated")
    void trustIsReplacedOnSave() {
        final Town town = foundRiftholm();

        final Town trusted = town.trust(OUTSIDER).orElseThrow();
        towns.save(trusted).join();
        assertThat(towns.find(town.id()).join().orElseThrow().trustedOutsiders())
                .containsExactly(OUTSIDER);

        towns.save(trusted.untrust(OUTSIDER).orElseThrow()).join();
        assertThat(towns.find(town.id()).join().orElseThrow().trustedOutsiders()).isEmpty();
    }

    @Test
    @DisplayName("joining and leaving a nation is persisted")
    void nationMembershipPersists() {
        final NationId valen = NationId.random();
        final Town town = foundRiftholm();

        towns.save(town.joinNation(valen).orElseThrow()).join();
        assertThat(towns.find(town.id()).join().orElseThrow().nation()).contains(valen);
        assertThat(towns.findByNation(valen).join()).hasSize(1);

        final Town aligned = towns.find(town.id()).join().orElseThrow();
        towns.save(aligned.leaveNation(true).orElseThrow()).join();
        assertThat(towns.find(town.id()).join().orElseThrow().nation()).isEmpty();
        assertThat(towns.findByNation(valen).join()).isEmpty();
    }

    @Test
    @DisplayName("disbanding releases residents rather than deleting the players")
    void disbandReleasesResidentsWithoutDeletingThem() {
        final Town town = foundRiftholm();

        assertThat(towns.delete(town.id()).join()).isTrue();

        assertThat(towns.find(town.id()).join()).isEmpty();
        final Resident formerMayor = residents.find(MAYOR).join().orElseThrow();
        assertThat(formerMayor.town())
                .as("the player still exists and is simply townless")
                .isEmpty();
    }

    @Test
    @DisplayName("disbanding removes the trust rows with the town")
    void disbandCascadesTrust() {
        final Town town = foundRiftholm();
        towns.save(town.trust(OUTSIDER).orElseThrow()).join();

        towns.delete(town.id()).join();

        final Town reborn = Town.found(town.id(), name("Riftholm"), MAYOR, UUID.randomUUID(), DAY_ONE);
        residents.save(Resident.restore(MAYOR, "Mayor", town.id(), DAY_ONE, DAY_ONE)).join();
        towns.save(reborn).join();

        assertThat(towns.find(town.id()).join().orElseThrow().trustedOutsiders())
                .as("a town recreated on the same id must not inherit the old trust list")
                .isEmpty();
    }

    @Test
    @DisplayName("deleting a town that is not there reports false rather than failing")
    void deletingAbsentTownIsFalse() {
        assertThat(towns.delete(TownId.random()).join()).isFalse();
    }
}
