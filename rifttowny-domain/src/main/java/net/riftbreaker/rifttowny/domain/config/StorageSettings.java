package net.riftbreaker.rifttowny.domain.config;

import java.util.Objects;

/**
 * Everything needed to open the database, independent of how it was configured.
 *
 * @param backend which database
 * @param jdbcUrl the fully-formed JDBC URL
 * @param username ignored by SQLite
 * @param password ignored by SQLite
 * @param poolSize maximum pooled connections
 * @param connectionTimeoutMillis how long to wait for a connection before failing
 */
public record StorageSettings(
        StorageBackend backend,
        String jdbcUrl,
        String username,
        String password,
        int poolSize,
        long connectionTimeoutMillis
) {

    /**
     * The smallest SQLite pool that cannot deadlock.
     *
     * <p>A pool of one deadlocks: several repositories open a second connection while still holding
     * the first, and the borrow then blocks until the timeout, which surfaces as a wrong answer
     * rather than an error. This has already cost the organisation a debugging session.</p>
     */
    public static final int MINIMUM_SQLITE_POOL_SIZE = 4;

    public StorageSettings {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        if (jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be at least 1, got " + poolSize);
        }
        if (connectionTimeoutMillis < 250L) {
            throw new IllegalArgumentException(
                    "connectionTimeoutMillis must be at least 250, got " + connectionTimeoutMillis);
        }
    }

    /** The pool size actually used, raising a SQLite pool to the safe minimum. */
    public int effectivePoolSize() {
        return backend == StorageBackend.SQLITE
                ? Math.max(poolSize, MINIMUM_SQLITE_POOL_SIZE)
                : poolSize;
    }

    /** A URL safe to log: SQLite paths are kept, server credentials never appear in a URL anyway. */
    public String describeForLog() {
        return backend + " (" + jdbcUrl + ")";
    }
}
