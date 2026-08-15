package net.riftbreaker.rifttowny.storage.migration;

import net.riftbreaker.rifttowny.domain.migration.MigrationPlan;
import net.riftbreaker.rifttowny.domain.migration.MigrationSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a Towny database.
 *
 * <p>Built against a database shaped like Towny's rather than against Towny itself, because Towny
 * cannot run here — RiftTowny disables itself in its presence, so there is no integration test to
 * write. What the fixtures encode is the schema as read out of Towny's own {@code SQLSchema} and
 * {@code TownyDBTableType}: table names, the {@code towny_} prefix, and the column names.</p>
 *
 * <p>The most valuable test here is not the happy path — it is
 * {@link Tolerance#readsATableMissingLaterColumns()}. Towny's schema grows version by version, and
 * a reader that assumed one shape would fail during somebody's migration, which is the worst
 * possible moment to discover a version difference.</p>
 */
class TownySqlSourceTest {

    private static final UUID BEDE = UUID.randomUUID();
    private static final UUID ADA = UUID.randomUUID();
    private static final UUID ROWAN = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final long REGISTERED = Instant.parse("2024-03-01T12:00:00Z").toEpochMilli();

    @TempDir
    private Path directory;

    private String url;
    private Connection connection;

    @BeforeEach
    void openDatabase() throws SQLException {
        url = "jdbc:sqlite:" + directory.resolve("towny.db").toAbsolutePath();
        connection = DriverManager.getConnection(url);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        connection.close();
    }

    private void sql(final String... statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (final String each : statements) {
                statement.execute(each);
            }
        }
    }

    /** Towny's tables, with the columns this reader looks for. */
    private void townySchema() throws SQLException {
        sql("""
                CREATE TABLE `towny_RESIDENTS` (
                  `name` VARCHAR(32) NOT NULL PRIMARY KEY, `uuid` VARCHAR(36),
                  `town` VARCHAR(32), `registered` BIGINT, `lastOnline` BIGINT,
                  `isNPC` BOOLEAN DEFAULT 0, `title` VARCHAR(32), `surname` VARCHAR(32))""",
                """
                CREATE TABLE `towny_TOWNS` (
                  `name` VARCHAR(32) NOT NULL PRIMARY KEY, `mayor` VARCHAR(32),
                  `nation` VARCHAR(32), `townBoard` VARCHAR(255), `tag` VARCHAR(8),
                  `open` BOOLEAN DEFAULT 0, `public` BOOLEAN DEFAULT 0,
                  `neutral` BOOLEAN DEFAULT 0, `ruined` BOOLEAN DEFAULT 0,
                  `registered` BIGINT, `homeblock` VARCHAR(64))""",
                """
                CREATE TABLE `towny_NATIONS` (
                  `name` VARCHAR(32) NOT NULL PRIMARY KEY, `capital` VARCHAR(32),
                  `nationBoard` VARCHAR(255), `tag` VARCHAR(8),
                  `neutral` BOOLEAN DEFAULT 0, `registered` BIGINT)""",
                """
                CREATE TABLE `towny_TOWNBLOCKS` (
                  `world` VARCHAR(36) NOT NULL, `x` MEDIUMINT NOT NULL, `z` MEDIUMINT NOT NULL,
                  `town` VARCHAR(32), `resident` VARCHAR(32), `outpost` BOOLEAN DEFAULT 0,
                  PRIMARY KEY (`world`, `x`, `z`))""",
                """
                CREATE TABLE `towny_WORLDS` (
                  `name` VARCHAR(32) NOT NULL PRIMARY KEY, `uuid` VARCHAR(36))""");
    }

    private void aWorldCalledEarth() throws SQLException {
        sql("INSERT INTO `towny_WORLDS` VALUES ('world', '" + WORLD + "')");
    }

    private MigrationPlan read() throws MigrationSource.MigrationException {
        return new TownySqlSource(url, "", "", null).read();
    }

    @Nested
    @DisplayName("a straightforward database")
    class HappyPath {

        @BeforeEach
        void populate() throws SQLException {
            townySchema();
            aWorldCalledEarth();
            sql("INSERT INTO `towny_RESIDENTS` (name, uuid, town, registered, lastOnline) VALUES "
                            + "('Bede', '" + BEDE + "', 'Ashford', " + REGISTERED + ", " + REGISTERED + ")",
                    "INSERT INTO `towny_RESIDENTS` (name, uuid, town, registered, lastOnline) VALUES "
                            + "('Ada', '" + ADA + "', 'Ashford', " + REGISTERED + ", " + REGISTERED + ")",
                    "INSERT INTO `towny_TOWNS` (name, mayor, nation, townBoard, tag, open, registered, homeblock) "
                            + "VALUES ('Ashford', 'Bede', 'Valen', 'Welcome', 'ASH', 1, " + REGISTERED
                            + ", 'world,3,4')",
                    "INSERT INTO `towny_NATIONS` (name, capital, nationBoard, tag, registered) "
                            + "VALUES ('Valen', 'Ashford', 'For Valen', 'VAL', " + REGISTERED + ")",
                    "INSERT INTO `towny_TOWNBLOCKS` (world, x, z, town, resident, outpost) VALUES "
                            + "('" + WORLD + "', 3, 4, 'Ashford', NULL, 0)",
                    "INSERT INTO `towny_TOWNBLOCKS` (world, x, z, town, resident, outpost) VALUES "
                            + "('" + WORLD + "', 3, 5, 'Ashford', 'Ada', 0)",
                    "INSERT INTO `towny_TOWNBLOCKS` (world, x, z, town, resident, outpost) VALUES "
                            + "('" + WORLD + "', 90, 90, 'Ashford', NULL, 1)");
        }

        @Test
        @DisplayName("reads residents with their accounts and history")
        void readsResidents() throws Exception {
            final MigrationPlan plan = read();

            assertThat(plan.residents()).hasSize(2);
            assertThat(plan.residents()).extracting(MigrationPlan.Resident::id)
                    .containsExactlyInAnyOrder(BEDE, ADA);
            assertThat(plan.residents().getFirst().joined())
                    .isEqualTo(Instant.ofEpochMilli(REGISTERED));
        }

        @Test
        @DisplayName("reads the town, its board, its tag and its openness")
        void readsTowns() throws Exception {
            final MigrationPlan.Town town = read().towns().getFirst();

            assertThat(town.name()).isEqualTo("Ashford");
            assertThat(town.mayorId()).isEqualTo(BEDE);
            assertThat(town.nationName()).isEqualTo("Valen");
            assertThat(town.board()).isEqualTo("Welcome");
            assertThat(town.tag()).isEqualTo("ASH");
            assertThat(town.open()).isTrue();
            assertThat(town.publicSpawn()).isFalse();
        }

        @Test
        @DisplayName("takes a nation's leader from its capital's mayor, because Towny has no king column")
        void nationLeaderComesFromTheCapital() throws Exception {
            // The mapping that would have been a wrong guess: Towny stores no leader on a nation.
            final MigrationPlan.Nation nation = read().nations().getFirst();

            assertThat(nation.name()).isEqualTo("Valen");
            assertThat(nation.capitalTownName()).isEqualTo("Ashford");
            assertThat(nation.kingId()).isEqualTo(BEDE);
            assertThat(nation.board()).isEqualTo("For Valen");
        }

        @Test
        @DisplayName("matches the homeblock back onto the chunk the town names")
        void findsTheHomeblock() throws Exception {
            // Towny records the homeblock on the town as "world,x,z"; the townblock rows carry no
            // flag of their own, so the two have to be matched up.
            final var claims = read().claims();

            assertThat(claims).hasSize(3);
            assertThat(claims).filteredOn(MigrationPlan.Claim::homeblock)
                    .singleElement()
                    .satisfies(claim -> {
                        assertThat(claim.chunkX()).isEqualTo(3);
                        assertThat(claim.chunkZ()).isEqualTo(4);
                    });
        }

        @Test
        @DisplayName("carries plot ownership and outposts across")
        void readsClaimDetail() throws Exception {
            final var claims = read().claims();

            assertThat(claims).filteredOn(claim -> claim.ownerId() != null)
                    .singleElement()
                    .extracting(MigrationPlan.Claim::ownerId).isEqualTo(ADA);
            assertThat(claims).filteredOn(MigrationPlan.Claim::outpost).hasSize(1);
            assertThat(claims).allSatisfy(claim -> assertThat(claim.worldId()).isEqualTo(WORLD));
        }
    }

    @Nested
    @DisplayName("what it leaves out, and says so")
    class Refusals {

        @BeforeEach
        void schema() throws SQLException {
            townySchema();
            aWorldCalledEarth();
        }

        @Test
        @DisplayName("a resident with no UUID, because a player cannot be matched by name alone")
        void residentsWithoutAccountsAreDropped() throws Exception {
            sql("INSERT INTO `towny_RESIDENTS` (name, uuid) VALUES ('Ancient', NULL)");

            final TownySqlSource source = new TownySqlSource(url, "", "", null);
            assertThat(source.read().residents()).isEmpty();
            assertThat(source.notes()).anyMatch(note -> note.contains("no UUID"));
        }

        @Test
        @DisplayName("Towny's own NPC accounts")
        void npcsAreDropped() throws Exception {
            sql("INSERT INTO `towny_RESIDENTS` (name, uuid, isNPC) VALUES "
                    + "('NPC1', '" + ROWAN + "', 1)");

            final TownySqlSource source = new TownySqlSource(url, "", "", null);
            assertThat(source.read().residents()).isEmpty();
            assertThat(source.notes()).anyMatch(note -> note.contains("NPC"));
        }

        @Test
        @DisplayName("a town Towny had already ruined, rather than resurrecting it")
        void ruinedTownsAreDropped() throws Exception {
            sql("INSERT INTO `towny_RESIDENTS` (name, uuid) VALUES ('Bede', '" + BEDE + "')",
                    "INSERT INTO `towny_TOWNS` (name, mayor, ruined) VALUES ('Fallen', 'Bede', 1)");

            final TownySqlSource source = new TownySqlSource(url, "", "", null);
            assertThat(source.read().towns()).isEmpty();
            assertThat(source.notes()).anyMatch(note -> note.contains("already ruined"));
        }

        @Test
        @DisplayName("a nation whose capital did not come across, since that is where its king is")
        void nationsWithoutACapitalAreDropped() throws Exception {
            sql("INSERT INTO `towny_NATIONS` (name, capital) VALUES ('Valen', 'Nowhere')");

            final TownySqlSource source = new TownySqlSource(url, "", "", null);
            assertThat(source.read().nations()).isEmpty();
            assertThat(source.notes()).anyMatch(note -> note.contains("capital"));
        }

        @Test
        @DisplayName("a town whose mayor is not importable")
        void mayorlessTownsAreDropped() throws Exception {
            sql("INSERT INTO `towny_TOWNS` (name, mayor) VALUES ('Ashford', 'Ghost')");

            final TownySqlSource source = new TownySqlSource(url, "", "", null);
            assertThat(source.read().towns()).isEmpty();
            assertThat(source.notes()).anyMatch(note -> note.contains("mayor"));
        }

        @Test
        @DisplayName("a town with no matching homeblock is reported rather than given one")
        void missingHomeblocksAreReported() throws Exception {
            sql("INSERT INTO `towny_RESIDENTS` (name, uuid) VALUES ('Bede', '" + BEDE + "')",
                    "INSERT INTO `towny_TOWNS` (name, mayor, homeblock) VALUES "
                            + "('Ashford', 'Bede', 'world,999,999')",
                    "INSERT INTO `towny_TOWNBLOCKS` (world, x, z, town) VALUES "
                            + "('" + WORLD + "', 1, 1, 'Ashford')");

            final TownySqlSource source = new TownySqlSource(url, "", "", null);
            final MigrationPlan plan = source.read();

            assertThat(plan.claims()).noneMatch(MigrationPlan.Claim::homeblock);
            assertThat(source.notes()).anyMatch(note -> note.contains("homeblock"));
        }
    }

    @Nested
    @DisplayName("tolerating other versions")
    class Tolerance {

        @Test
        @DisplayName("reads a table missing columns a later Towny added")
        void readsATableMissingLaterColumns() throws Exception {
            // The point of reading by metadata rather than naming columns in the SELECT. Towny's
            // SQLSchema adds columns as versions land; a reader that assumed one shape would fail
            // wholesale during somebody's migration.
            sql("""
                    CREATE TABLE `towny_RESIDENTS` (
                      `name` VARCHAR(32) NOT NULL PRIMARY KEY, `uuid` VARCHAR(36), `town` VARCHAR(32))""",
                    """
                    CREATE TABLE `towny_TOWNS` (
                      `name` VARCHAR(32) NOT NULL PRIMARY KEY, `mayor` VARCHAR(32))""",
                    "CREATE TABLE `towny_NATIONS` (`name` VARCHAR(32) NOT NULL PRIMARY KEY)",
                    """
                    CREATE TABLE `towny_TOWNBLOCKS` (
                      `world` VARCHAR(36) NOT NULL, `x` MEDIUMINT, `z` MEDIUMINT, `town` VARCHAR(32))""",
                    "CREATE TABLE `towny_WORLDS` (`name` VARCHAR(32) NOT NULL PRIMARY KEY)");
            sql("INSERT INTO `towny_RESIDENTS` VALUES ('Bede', '" + BEDE + "', 'Ashford')",
                    "INSERT INTO `towny_TOWNS` VALUES ('Ashford', 'Bede')",
                    "INSERT INTO `towny_TOWNBLOCKS` VALUES ('" + WORLD + "', 0, 0, 'Ashford')");

            final MigrationPlan plan = read();

            assertThat(plan.towns()).singleElement()
                    .satisfies(town -> {
                        assertThat(town.name()).isEqualTo("Ashford");
                        // Absent columns read as absent, not as an exception.
                        assertThat(town.board()).isNull();
                        assertThat(town.open()).isFalse();
                        assertThat(town.founded()).isNull();
                    });
            assertThat(plan.claims()).hasSize(1);
        }

        @Test
        @DisplayName("places a townblock that names its world by name rather than by UUID")
        void olderWorldReferencesStillResolve() throws Exception {
            townySchema();
            aWorldCalledEarth();
            sql("INSERT INTO `towny_RESIDENTS` (name, uuid) VALUES ('Bede', '" + BEDE + "')",
                    "INSERT INTO `towny_TOWNS` (name, mayor) VALUES ('Ashford', 'Bede')",
                    "INSERT INTO `towny_TOWNBLOCKS` (world, x, z, town) VALUES ('world', 0, 0, 'Ashford')");

            assertThat(read().claims()).singleElement()
                    .extracting(MigrationPlan.Claim::worldId).isEqualTo(WORLD);
        }

        @Test
        @DisplayName("a zero timestamp is absent, not 1970")
        void zeroTimestampsAreAbsent() throws Exception {
            // Towny writes 0 for "never". Importing it would make every listing sorted by age
            // nonsense, with half the server founded at the epoch.
            townySchema();
            sql("INSERT INTO `towny_RESIDENTS` (name, uuid, registered) VALUES "
                    + "('Bede', '" + BEDE + "', 0)");

            assertThat(read().residents()).singleElement()
                    .extracting(MigrationPlan.Resident::joined).isNull();
        }

        @Test
        @DisplayName("a resident's town column is a UUID, and resolves to the town's name")
        void residentTownColumnIsAUuid() throws Exception {
            // Same silent failure as the flatfile reader had: Towny parses rt_residents.town as a
            // town UUID with a name fallback, and the importer joins on names. Left unresolved,
            // every town imports holding only its mayor.
            final String ashford = UUID.randomUUID().toString();
            townySchema();
            sql("ALTER TABLE `towny_TOWNS` ADD COLUMN `uuid` VARCHAR(36)",
                    "INSERT INTO `towny_RESIDENTS` (name, uuid, town) VALUES "
                            + "('Bede', '" + BEDE + "', '" + ashford + "')",
                    "INSERT INTO `towny_RESIDENTS` (name, uuid, town) VALUES "
                            + "('Ada', '" + ADA + "', '" + ashford + "')",
                    "INSERT INTO `towny_TOWNS` (name, mayor, uuid) VALUES "
                            + "('Ashford', 'Bede', '" + ashford + "')");

            assertThat(read().residents())
                    .hasSize(2)
                    .allSatisfy(resident ->
                            assertThat(resident.townName()).isEqualTo("Ashford"));
        }

        @Test
        @DisplayName("a resident's town given as a name still resolves")
        void residentTownColumnMayBeAName() throws Exception {
            townySchema();
            sql("INSERT INTO `towny_RESIDENTS` (name, uuid, town) VALUES "
                            + "('Bede', '" + BEDE + "', 'Ashford')",
                    "INSERT INTO `towny_TOWNS` (name, mayor) VALUES ('Ashford', 'Bede')");

            assertThat(read().residents()).singleElement()
                    .extracting(MigrationPlan.Resident::townName).isEqualTo("Ashford");
        }

        @Test
        @DisplayName("a custom table prefix is honoured")
        void customPrefixIsUsed() throws Exception {
            sql("CREATE TABLE `tny_RESIDENTS` (`name` VARCHAR(32), `uuid` VARCHAR(36))",
                    "CREATE TABLE `tny_TOWNS` (`name` VARCHAR(32))",
                    "CREATE TABLE `tny_NATIONS` (`name` VARCHAR(32))",
                    "CREATE TABLE `tny_TOWNBLOCKS` (`world` VARCHAR(36))",
                    "CREATE TABLE `tny_WORLDS` (`name` VARCHAR(32))",
                    "INSERT INTO `tny_RESIDENTS` VALUES ('Bede', '" + BEDE + "')");

            assertThat(new TownySqlSource(url, "", "", "tny_").read().residents()).hasSize(1);
        }

        @Test
        @DisplayName("a database that is not Towny's fails with a message naming the problem")
        void anUnreadableDatabaseSaysSo() {
            assertThatThrownBy(() -> read())
                    .isInstanceOf(MigrationSource.MigrationException.class)
                    .hasMessageContaining("Could not read the Towny database");
        }
    }
}
