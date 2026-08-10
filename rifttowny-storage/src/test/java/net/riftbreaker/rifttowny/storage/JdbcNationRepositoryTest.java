package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.Nation;
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

class JdbcNationRepositoryTest extends SqliteFixture {

    private static final Instant DAY_ONE = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant DAY_TWO = Instant.parse("2026-08-02T10:00:00Z");

    private static final ResidentId ALDER = ResidentId.of(UUID.randomUUID());
    private static final ResidentId BRIAR = ResidentId.of(UUID.randomUUID());

    private JdbcNationRepository nations;
    private JdbcTownRepository towns;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createRepositories() {
        nations = new JdbcNationRepository(database, DIRECT);
        towns = new JdbcTownRepository(database, DIRECT);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private static OrganisationName name(final String raw) {
        return NamePolicy.defaults().check(raw).accepted().orElseThrow();
    }

    /** Founds a town in the order storage requires: the resident row, then the town. */
    private Town foundTown(final String townName, final ResidentId mayor, final Instant when) {
        final Town town = Town.found(TownId.random(), name(townName), mayor, UUID.randomUUID(), when);
        residents.save(Resident.newcomer(mayor, townName + "Mayor", when)
                .joinTown(town.id()).orElseThrow()).join();
        towns.save(town).join();
        return town;
    }

    /** Founds a nation, aligning its capital town first so the allegiance row exists. */
    private Nation foundNation(final String nationName, final Town capital) {
        towns.save(capital.joinNation(NATION_ID).orElseThrow()).join();
        final Nation nation = Nation.found(
                NATION_ID, name(nationName), ALDER, capital.id(), UUID.randomUUID(), DAY_ONE);
        nations.save(nation).join();
        return nation;
    }

    private static final NationId NATION_ID = NationId.random();

    @Test
    @DisplayName("a saved nation round-trips with its capital, leader, account and towns")
    void saveAndFind() {
        final Town riftholm = foundTown("Riftholm", ALDER, DAY_ONE);
        final Nation valen = foundNation("Valen", riftholm);

        final Nation loaded = nations.find(valen.id()).join().orElseThrow();

        assertThat(loaded.id()).isEqualTo(valen.id());
        assertThat(loaded.name().display()).isEqualTo("Valen");
        assertThat(loaded.leader()).isEqualTo(ALDER);
        assertThat(loaded.capital()).isEqualTo(riftholm.id());
        assertThat(loaded.bankAccountId()).isEqualTo(valen.bankAccountId());
        assertThat(loaded.towns()).containsExactly(riftholm.id());
    }

    @Test
    @DisplayName("an unknown nation is empty, not an error")
    void unknownNationIsEmpty() {
        assertThat(nations.find(NationId.random()).join()).isEmpty();
    }

    @Test
    @DisplayName("lookup by name uses the normalised column")
    void findByNameIsCaseInsensitive() {
        foundNation("Valen", foundTown("Riftholm", ALDER, DAY_ONE));

        assertThat(nations.findByName("valen").join()).isPresent();
        assertThat(nations.findByName("VALEN").join()).isPresent();
        assertThat(nations.findByName("Korath").join()).isEmpty();
    }

    @Test
    @DisplayName("membership comes from the town table, so saving a nation cannot invent members")
    void membershipIsOwnedByTheTownTable() {
        final Town riftholm = foundTown("Riftholm", ALDER, DAY_ONE);
        final Nation valen = foundNation("Valen", riftholm);
        final Town ashford = foundTown("Ashford", BRIAR, DAY_TWO);

        // A nation aggregate that believes Ashford joined, saved without Ashford's own row ever
        // recording the allegiance.
        nations.save(valen.admit(ashford.id()).orElseThrow()).join();

        assertThat(nations.find(valen.id()).join().orElseThrow().towns())
                .as("rt_town.nation_id is the only record of allegiance")
                .containsExactly(riftholm.id());
    }

    @Test
    @DisplayName("a town joining is visible once its own row records the allegiance")
    void townJoinIsVisibleThroughTheTownRow() {
        final Town riftholm = foundTown("Riftholm", ALDER, DAY_ONE);
        final Nation valen = foundNation("Valen", riftholm);
        final Town ashford = foundTown("Ashford", BRIAR, DAY_TWO);

        towns.save(ashford.joinNation(valen.id()).orElseThrow()).join();

        assertThat(nations.find(valen.id()).join().orElseThrow().towns())
                .containsExactly(riftholm.id(), ashford.id());
    }

    @Test
    @DisplayName("moving the capital is persisted and keeps the bank account")
    void capitalMovePersists() {
        final Town riftholm = foundTown("Riftholm", ALDER, DAY_ONE);
        final Nation valen = foundNation("Valen", riftholm);
        final Town ashford = foundTown("Ashford", BRIAR, DAY_TWO);
        towns.save(ashford.joinNation(valen.id()).orElseThrow()).join();

        final Nation reloaded = nations.find(valen.id()).join().orElseThrow();
        nations.save(reloaded.moveCapital(ashford.id()).orElseThrow()).join();

        final Nation loaded = nations.find(valen.id()).join().orElseThrow();
        assertThat(loaded.capital()).isEqualTo(ashford.id());
        assertThat(loaded.bankAccountId()).isEqualTo(valen.bankAccountId());
    }

    @Test
    @DisplayName("renaming and transferring leadership keep the id and the bank account")
    void renameAndTransferKeepIdentity() {
        final Nation valen = foundNation("Valen", foundTown("Riftholm", ALDER, DAY_ONE));

        nations.save(valen.renameTo(name("Korath")).orElseThrow()
                .transferLeadership(BRIAR, true).orElseThrow()).join();

        final Nation loaded = nations.find(valen.id()).join().orElseThrow();
        assertThat(loaded.name().display()).isEqualTo("Korath");
        assertThat(loaded.leader()).isEqualTo(BRIAR);
        assertThat(loaded.id()).isEqualTo(valen.id());
        assertThat(loaded.bankAccountId()).isEqualTo(valen.bankAccountId());
    }

    @Test
    @DisplayName("dissolving a nation releases its towns rather than deleting them")
    void dissolveReleasesTownsWithoutDeletingThem() {
        final Town riftholm = foundTown("Riftholm", ALDER, DAY_ONE);
        final Nation valen = foundNation("Valen", riftholm);

        assertThat(nations.delete(valen.id()).join()).isTrue();

        assertThat(nations.find(valen.id()).join()).isEmpty();
        final Town survivor = towns.find(riftholm.id()).join().orElseThrow();
        assertThat(survivor.nation())
                .as("the town still exists and is simply unaligned")
                .isEmpty();
    }

    @Test
    @DisplayName("deleting a nation that is not there reports false rather than failing")
    void deletingAbsentNationIsFalse() {
        assertThat(nations.delete(NationId.random()).join()).isFalse();
    }

    @Test
    @DisplayName("counting and listing see every nation")
    void countAndListing() {
        foundNation("Valen", foundTown("Riftholm", ALDER, DAY_ONE));

        assertThat(nations.count().join()).isEqualTo(1);
        assertThat(nations.all().join()).extracting(nation -> nation.name().display())
                .containsExactly("Valen");
    }
}
