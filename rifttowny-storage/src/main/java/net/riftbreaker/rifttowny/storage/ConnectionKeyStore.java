package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Objects;

/**
 * Idempotency keys on the transaction's own connection.
 *
 * <p>The primary key on {@code idempotency_key} is the whole mechanism. Taking a key is an insert
 * that either succeeds or collides, and because it happens on the same connection as the work it
 * guards, the two share a fate: roll back the transaction and the key goes with it.</p>
 */
final class ConnectionKeyStore implements CivicTransaction.KeyStore {

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionKeyStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public boolean claim(final String key, final String scope, final Instant now) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(now, "now");

        // Insert-or-nothing rather than select-then-insert. Two servers sharing a database can run
        // the check at the same moment and both see it free; only one can win the primary key.
        final String sql = switch (backend) {
            case SQLITE -> "INSERT INTO rt_idempotency (idempotency_key, scope, created_at) "
                    + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
            case MARIADB -> "INSERT IGNORE INTO rt_idempotency (idempotency_key, scope, created_at) "
                    + "VALUES (?, ?, ?)";
        };
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, key);
                statement.setString(2, scope);
                statement.setLong(3, now.toEpochMilli());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean holds(final String key) {
        Objects.requireNonNull(key, "key");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM rt_idempotency WHERE idempotency_key = ?")) {
                statement.setString(1, key);
                try (ResultSet results = statement.executeQuery()) {
                    return results.next();
                }
            }
        });
    }

    @Override
    public int prune(final Instant before) {
        Objects.requireNonNull(before, "before");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_idempotency WHERE created_at < ?")) {
                statement.setLong(1, before.toEpochMilli());
                return statement.executeUpdate();
            }
        });
    }
}
