package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcResidentRepositoryTest extends SqliteFixture {

    private static final Instant DAY_ONE = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant DAY_TWO = Instant.parse("2026-08-02T10:00:00Z");

    private static final TownId RIFTHOLM = TownId.random();
    private static final TownId ASHFORD = TownId.random();

    private JdbcResidentRepository residents;

    @BeforeEach
    void createRepository() throws Exception {
        residents = new JdbcResidentRepository(database, DIRECT);
        // rt_resident.town_id has no foreign key in V1, but the towns are inserted anyway so the
        // fixture matches production shape rather than a shape only the test can produce.
        insertTown(RIFTHOLM, "Riftholm");
        insertTown(ASHFORD, "Ashford");
    }

    private Resident newcomer(final String name, final Instant seen) {
        return Resident.newcomer(ResidentId.of(UUID.randomUUID()), name, seen);
    }

    @Test
    @DisplayName("a saved resident round-trips exactly")
    void saveAndFind() {
        final Resident alder = newcomer("Alder", DAY_ONE);

        residents.save(alder).join();

        final Resident loaded = residents.find(alder.id()).join().orElseThrow();
        assertThat(loaded.id()).isEqualTo(alder.id());
        assertThat(loaded.lastKnownName()).isEqualTo("Alder");
        assertThat(loaded.town()).isEmpty();
        assertThat(loaded.joinedAt()).isEqualTo(DAY_ONE);
        assertThat(loaded.lastSeenAt()).isEqualTo(DAY_ONE);
    }

    @Test
    @DisplayName("an unknown resident is empty, not an error")
    void unknownResidentIsEmpty() {
        assertThat(residents.find(ResidentId.of(UUID.randomUUID())).join()).isEmpty();
    }

    @Test
    @DisplayName("saving twice updates rather than duplicating, so a rejoin cannot clone a player")
    void saveIsAnUpsert() {
        final Resident alder = newcomer("Alder", DAY_ONE);
        residents.save(alder).join();

        residents.save(alder.joinTown(RIFTHOLM).orElseThrow().seenAt(DAY_TWO)).join();

        final Resident loaded = residents.find(alder.id()).join().orElseThrow();
        assertThat(loaded.town()).contains(RIFTHOLM);
        assertThat(loaded.lastSeenAt()).isEqualTo(DAY_TWO);
        assertThat(residents.findByTown(RIFTHOLM).join().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("joinedAt is never moved by a later save, so first-seen history survives")
    void joinedAtIsImmutableAcrossSaves() {
        final Resident alder = newcomer("Alder", DAY_ONE);
        residents.save(alder).join();

        residents.save(alder.seenAt(DAY_TWO)).join();

        assertThat(residents.find(alder.id()).join().orElseThrow().joinedAt()).isEqualTo(DAY_ONE);
    }

    @Test
    @DisplayName("leaving a town clears the column rather than leaving a dangling id")
    void leavingClearsTheTown() {
        final Resident alder = newcomer("Alder", DAY_ONE);
        final Resident joined = alder.joinTown(RIFTHOLM).orElseThrow();
        residents.save(joined).join();

        residents.save(joined.leaveTown().orElseThrow()).join();

        assertThat(residents.find(alder.id()).join().orElseThrow().town()).isEmpty();
        assertThat(residents.findByTown(RIFTHOLM).join().size()).isZero();
    }

    @Test
    @DisplayName("a town's residents come back in join order, not hash order")
    void townResidentsAreOrderedByJoinTime() {
        final Resident first = Resident.newcomer(ResidentId.of(UUID.randomUUID()), "First", DAY_ONE);
        final Resident second = Resident.newcomer(ResidentId.of(UUID.randomUUID()), "Second", DAY_TWO);
        residents.save(first.joinTown(RIFTHOLM).orElseThrow()).join();
        residents.save(second.joinTown(RIFTHOLM).orElseThrow()).join();

        assertThat(residents.findByTown(RIFTHOLM).join())
                .extracting(Resident::lastKnownName)
                .containsExactly("First", "Second");
    }

    @Test
    @DisplayName("residents of one town are not returned for another")
    void townMembershipIsScoped() {
        residents.save(newcomer("Alder", DAY_ONE).joinTown(RIFTHOLM).orElseThrow()).join();
        residents.save(newcomer("Briar", DAY_ONE).joinTown(ASHFORD).orElseThrow()).join();

        assertThat(residents.findByTown(RIFTHOLM).join()).hasSize(1);
        assertThat(residents.findByTown(ASHFORD).join().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("name lookup is case-insensitive on both backends, not just where the collation is")
    void nameLookupIsCaseInsensitive() {
        residents.save(newcomer("Alder", DAY_ONE)).join();

        assertThat(residents.findByName("alder").join()).isPresent();
        assertThat(residents.findByName("ALDER").join()).isPresent();
        assertThat(residents.findByName("Briar").join()).isEmpty();
    }

    @Test
    @DisplayName("a Minecraft name change updates the row without changing identity")
    void renameKeepsTheSameRow() {
        final Resident alder = newcomer("Alder", DAY_ONE);
        residents.save(alder).join();

        residents.save(alder.renamedTo("Alder_Two")).join();

        assertThat(residents.findByName("Alder").join()).isEmpty();
        assertThat(residents.findByName("Alder_Two").join())
                .get()
                .extracting(Resident::id)
                .isEqualTo(alder.id());
    }

    private void insertTown(final TownId id, final String name) throws Exception {
        database.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO rt_town (town_id, name, name_normalised, bank_account_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, id.value().toString());
                statement.setString(2, name);
                statement.setString(3, name.toLowerCase(java.util.Locale.ROOT));
                statement.setString(4, UUID.randomUUID().toString());
                statement.setLong(5, DAY_ONE.toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }
}
