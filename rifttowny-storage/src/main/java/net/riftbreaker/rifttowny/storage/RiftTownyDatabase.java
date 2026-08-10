package net.riftbreaker.rifttowny.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.config.StorageSettings;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The pooled connection source, plus the two things the pool alone does not give us:
 * dialect-appropriate tuning, and write serialisation on SQLite.
 */
public final class RiftTownyDatabase implements AutoCloseable {

    private final StorageBackend backend;
    private final HikariDataSource dataSource;

    /**
     * SQLite allows one writer at a time. Serialising writes here turns a
     * {@code SQLITE_BUSY} race into a short wait, which is what an operator expects from a
     * single-server database. On MariaDB the lock is never taken.
     */
    private final ReentrantLock writeLock = new ReentrantLock();

    private RiftTownyDatabase(final StorageBackend backend, final HikariDataSource dataSource) {
        this.backend = backend;
        this.dataSource = dataSource;
    }

    /** Opens the pool. Does not migrate — see {@link SchemaMigrator}. */
    public static RiftTownyDatabase open(final StorageSettings settings) {
        Objects.requireNonNull(settings, "settings");

        final HikariConfig config = new HikariConfig();
        config.setPoolName("RiftTowny-" + settings.backend().name().toLowerCase(java.util.Locale.ROOT));
        config.setJdbcUrl(settings.jdbcUrl());
        config.setMaximumPoolSize(settings.effectivePoolSize());
        config.setConnectionTimeout(settings.connectionTimeoutMillis());
        config.setAutoCommit(true);

        if (settings.backend() == StorageBackend.MARIADB) {
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        } else {
            // Applied per connection because SQLite settings are connection-scoped, not
            // database-scoped: a pooled connection that skipped these would silently run without
            // foreign keys, which is exactly the kind of divergence that only shows up in
            // production data.
            config.setConnectionInitSql(
                    "PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000; "
                            + "PRAGMA synchronous=NORMAL");
        }

        return new RiftTownyDatabase(settings.backend(), new HikariDataSource(config));
    }

    public StorageBackend backend() {
        return backend;
    }

    /** Borrows a connection. The caller must close it, normally with try-with-resources. */
    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Runs a write, serialised on SQLite and unserialised on MariaDB.
     *
     * <p>Everything that writes goes through here rather than taking a connection directly, so the
     * SQLite lock cannot be forgotten at one call site.</p>
     */
    public <T> T write(final SqlFunction<T> work) throws SQLException {
        final boolean serialise = backend == StorageBackend.SQLITE;
        if (serialise) {
            writeLock.lock();
        }
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } finally {
            if (serialise) {
                writeLock.unlock();
            }
        }
    }

    /** Runs a read. Never takes the write lock. */
    public <T> T read(final SqlFunction<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        }
    }

    /**
     * Runs several statements as one transaction, serialised on SQLite.
     *
     * <p>Restores the connection's auto-commit state before returning it to the pool; a pooled
     * connection left with auto-commit off is a bug that appears in an unrelated query later.</p>
     */
    public <T> T transaction(final SqlFunction<T> work) throws SQLException {
        final boolean serialise = backend == StorageBackend.SQLITE;
        if (serialise) {
            writeLock.lock();
        }
        try (Connection connection = dataSource.getConnection()) {
            final boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                final T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (final SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } finally {
            if (serialise) {
                writeLock.unlock();
            }
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    /** A unit of JDBC work that may fail with {@link SQLException}. */
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}
