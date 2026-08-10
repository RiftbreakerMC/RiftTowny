package net.riftbreaker.rifttowny.domain.config;

import java.util.Locale;

/** The two supported databases. PostgreSQL is deliberately out of scope. */
public enum StorageBackend {

    /**
     * Production and any multi-server install. Real transactions, row locking and advisory locks.
     */
    MARIADB(true),

    /**
     * Development and single-server installs only.
     *
     * <p>SQLite has no cross-process locking model that survives two servers writing the same file
     * over a network share, so RiftTowny refuses to start rather than corrupt data slowly.</p>
     */
    SQLITE(false);

    private final boolean supportsSharedTopology;

    StorageBackend(final boolean supportsSharedTopology) {
        this.supportsSharedTopology = supportsSharedTopology;
    }

    /** Whether more than one backend server may share this database. */
    public boolean supportsSharedTopology() {
        return supportsSharedTopology;
    }

    /**
     * Parses a configured value.
     *
     * @throws IllegalArgumentException naming the accepted values, because "mysql" and "maria" are
     *         the obvious things an operator types and a bare enum failure would not say so
     */
    public static StorageBackend parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("storage.backend is required; accepted values: mariadb, sqlite");
        }
        final String normalised = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "mariadb", "mysql", "maria" -> MARIADB;
            case "sqlite", "sqlite3", "file" -> SQLITE;
            default -> throw new IllegalArgumentException(
                    "Unknown storage.backend '" + raw + "'; accepted values: mariadb, sqlite");
        };
    }
}
