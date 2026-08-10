package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

import java.util.Objects;
import java.util.Optional;

/**
 * Applies the schema.
 *
 * <p>One migration set per dialect under {@code db/migration/<dialect>}. The two files are kept
 * deliberately parallel so a change to one is obviously a change to the other; splitting them is
 * only what the dialects force (auto-increment syntax, engine and charset clauses).</p>
 */
public final class SchemaMigrator {

    private final RiftTownyDatabase database;
    private final ClassLoader classLoader;

    /**
     * @param classLoader <strong>the plugin's own classloader.</strong> Flyway scans for migrations
     *        through a classloader, and the thread context classloader under a Bukkit plugin sees
     *        the server's classpath, not the plugin's — Flyway then reports "no migrations found"
     *        and creates an empty schema rather than failing. Same trap as {@code ServiceLoader}.
     */
    public SchemaMigrator(final RiftTownyDatabase database, final ClassLoader classLoader) {
        this.database = Objects.requireNonNull(database, "database");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /** The migration location for a backend. */
    public static String locationFor(final StorageBackend backend) {
        return switch (backend) {
            case MARIADB -> "classpath:db/migration/mariadb";
            case SQLITE -> "classpath:db/migration/sqlite";
        };
    }

    /**
     * Applies every outstanding migration.
     *
     * @return a description of what was applied, for the startup log
     * @throws IllegalStateException if the migration set could not be found at all, which almost
     *         always means the classloader was wrong rather than that the schema is up to date
     */
    public MigrationSummary migrate() {
        final String location = locationFor(database.backend());

        final Flyway flyway = Flyway.configure(classLoader)
                .dataSource(unwrapDataSource())
                .locations(location)
                .table("rt_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .load();

        final MigrationInfo[] all = flyway.info().all();
        if (all.length == 0) {
            throw new IllegalStateException(
                    "No RiftTowny migrations were found at " + location + ". This normally means "
                            + "Flyway was given the wrong classloader rather than that the schema is "
                            + "current; continuing would create an empty database.");
        }

        final MigrateResult result = flyway.migrate();
        final String current = Optional.ofNullable(flyway.info().current())
                .map(info -> info.getVersion().getVersion())
                .orElse("none");

        return new MigrationSummary(
                database.backend(), location, result.migrationsExecuted, current, all.length);
    }

    private javax.sql.DataSource unwrapDataSource() {
        // Flyway wants a DataSource; the pool is the DataSource. Going through the pool rather than
        // opening a bare connection means migrations get the same SQLite pragmas as everything else.
        return new javax.sql.DataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                return database.connection();
            }

            @Override
            public java.sql.Connection getConnection(final String username, final String password)
                    throws java.sql.SQLException {
                return database.connection();
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(final java.io.PrintWriter out) {
                // Pool-managed; nothing to do.
            }

            @Override
            public void setLoginTimeout(final int seconds) {
                // Pool-managed; nothing to do.
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getLogger("RiftTowny.Storage");
            }

            @Override
            public <T> T unwrap(final Class<T> iface) throws java.sql.SQLException {
                throw new java.sql.SQLException("Not a wrapper for " + iface.getName());
            }

            @Override
            public boolean isWrapperFor(final Class<?> iface) {
                return false;
            }
        };
    }

    /**
     * What {@link #migrate()} did.
     *
     * @param backend which dialect
     * @param location where migrations were read from
     * @param applied how many ran this time
     * @param currentVersion the schema version now in place
     * @param available how many migrations exist in total
     */
    public record MigrationSummary(
            StorageBackend backend,
            String location,
            int applied,
            String currentVersion,
            int available
    ) {
        public String describe() {
            return "Schema " + currentVersion + " on " + backend + " (" + applied + " of "
                    + available + " migration(s) applied this start, from " + location + ')';
        }
    }
}
