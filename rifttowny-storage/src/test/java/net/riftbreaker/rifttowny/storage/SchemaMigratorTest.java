package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.config.StorageSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigratorTest {

    @TempDir
    Path directory;

    private RiftTownyDatabase database;

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    private RiftTownyDatabase open(final String fileName) {
        return RiftTownyDatabase.open(new StorageSettings(
                StorageBackend.SQLITE,
                "jdbc:sqlite:" + directory.resolve(fileName).toAbsolutePath(),
                "", "", StorageSettings.MINIMUM_SQLITE_POOL_SIZE, 5_000L));
    }

    /**
     * The version the migration set is currently at.
     *
     * <p>Asserted rather than hard-coded at each call site so adding a migration is a one-line
     * change here, and a migration added to only one of the two dialect directories still shows up
     * as a failure rather than passing quietly.</p>
     */
    private static final String CURRENT_SCHEMA_VERSION = "19";

    private static final int MIGRATION_COUNT = 19;

    @Test
    @DisplayName("the migrations apply and create every table the plan names")
    void migrationsCreateEveryTable() throws Exception {
        database = open("apply.db");

        final SchemaMigrator.MigrationSummary summary =
                new SchemaMigrator(database, getClass().getClassLoader()).migrate();

        assertThat(summary.applied()).isEqualTo(MIGRATION_COUNT);
        assertThat(summary.currentVersion()).isEqualTo(CURRENT_SCHEMA_VERSION);
        assertThat(summary.backend()).isEqualTo(StorageBackend.SQLITE);

        assertThat(tableNames()).contains(
                "rt_resident", "rt_town", "rt_nation", "rt_claim", "rt_area",
                "rt_role", "rt_role_permission", "rt_role_member",
                "rt_organisation_currency", "rt_outbox", "rt_idempotency", "rt_audit",
                "rt_town_trust", "rt_flag_override", "rt_invitation", "rt_ruin", "rt_ruin_claim", "rt_town_spawn", "rt_organisation_balance", "rt_bank_ledger", "rt_tax_run", "rt_nation_relation", "rt_town_outlaw", "rt_resident_preference");
    }

    @Test
    @DisplayName("migrating an already-migrated database is a no-op, not a failure")
    void migratingTwiceIsIdempotent() {
        database = open("twice.db");
        final ClassLoader loader = getClass().getClassLoader();

        assertThat(new SchemaMigrator(database, loader).migrate().applied())
                .isEqualTo(MIGRATION_COUNT);
        final SchemaMigrator.MigrationSummary second = new SchemaMigrator(database, loader).migrate();

        assertThat(second.applied()).isZero();
        assertThat(second.currentVersion()).isEqualTo(CURRENT_SCHEMA_VERSION);
    }

    @Test
    @DisplayName("both dialects carry the same number of migrations, so they cannot drift")
    void bothDialectsHaveTheSameMigrationCount() throws Exception {
        for (final StorageBackend backend : StorageBackend.values()) {
            final String location = SchemaMigrator.locationFor(backend)
                    .replace("classpath:", "");
            final java.net.URL directory = getClass().getClassLoader().getResource(location);
            assertThat(directory).as("migration directory for %s", backend).isNotNull();

            final java.io.File[] files =
                    new java.io.File(directory.toURI()).listFiles((dir, fileName) -> fileName.endsWith(".sql"));
            assertThat(files).as("migrations for %s", backend).hasSize(MIGRATION_COUNT);
        }
    }

    @Test
    @DisplayName("a classloader that cannot see the migrations fails loudly instead of creating an empty schema")
    void missingMigrationsFailLoudly() {
        database = open("empty.db");

        // The platform classloader cannot see the plugin jar's resources - the same situation a
        // Bukkit plugin creates when it hands Flyway the thread context classloader. Flyway's own
        // behaviour there is to find nothing and cheerfully create an empty schema, which is the
        // failure this guard exists to convert into a startup error.
        final SchemaMigrator migrator =
                new SchemaMigrator(database, ClassLoader.getPlatformClassLoader());

        org.assertj.core.api.Assertions.assertThatThrownBy(migrator::migrate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classloader");
    }

    @Test
    @DisplayName("the claim table refuses two towns claiming one chunk")
    void chunkUniquenessIsEnforcedByTheSchema() throws Exception {
        database = open("claims.db");
        new SchemaMigrator(database, getClass().getClassLoader()).migrate();

        final String world = "11111111-1111-1111-1111-111111111111";
        insertTown("aaaaaaaa-0000-0000-0000-000000000001", "Alpha");
        insertTown("bbbbbbbb-0000-0000-0000-000000000002", "Beta");

        insertClaim("cccccccc-0000-0000-0000-000000000001", world, 4, 9,
                "aaaaaaaa-0000-0000-0000-000000000001");

        assertThatChunkClaimIsRejected(world, 4, 9, "bbbbbbbb-0000-0000-0000-000000000002");
    }

    private void assertThatChunkClaimIsRejected(
            final String world, final int x, final int z, final String townId) {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> insertClaim(
                        "dddddddd-0000-0000-0000-000000000002", world, x, z, townId))
                .isInstanceOf(java.sql.SQLException.class);
    }

    private void insertTown(final String townId, final String name) throws Exception {
        database.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO rt_town (town_id, name, name_normalised, bank_account_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, townId);
                statement.setString(2, name);
                statement.setString(3, name.toLowerCase(java.util.Locale.ROOT));
                statement.setString(4, townId);
                statement.setLong(5, 0L);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void insertClaim(
            final String claimId,
            final String worldId,
            final int chunkX,
            final int chunkZ,
            final String townId
    ) throws Exception {
        database.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO rt_claim (claim_id, world_id, chunk_x, chunk_z, town_id, "
                            + "claim_kind, claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, claimId);
                statement.setString(2, worldId);
                statement.setInt(3, chunkX);
                statement.setInt(4, chunkZ);
                statement.setString(5, townId);
                statement.setString(6, "ORDINARY");
                statement.setLong(7, 0L);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private List<String> tableNames() throws Exception {
        return database.read(connection -> {
            final List<String> names = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type = 'table'");
                 ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    names.add(results.getString(1));
                }
            }
            return names;
        });
    }
}
