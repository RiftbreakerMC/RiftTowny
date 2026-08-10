package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.idempotency.IdempotencyRecord;
import net.riftbreaker.rifttowny.domain.idempotency.IdempotencyStore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** JDBC idempotency keys, working identically on MariaDB and SQLite. */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private final RiftTownyDatabase database;
    private final Executor executor;

    public JdbcIdempotencyStore(final RiftTownyDatabase database, final Executor executor) {
        this.database = Objects.requireNonNull(database, "database");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<Boolean> claim(final String key, final String scope, final Instant now) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(now, "now");

        return supply(() -> database.write(connection -> {
            // The primary key does the work: whoever inserts the row first wins, and the loser gets
            // zero affected rows rather than an exception to interpret. A select-then-insert would
            // let two servers both decide they were first.
            final String sql = switch (database.backend()) {
                case SQLITE -> "INSERT OR IGNORE INTO rt_idempotency";
                case MARIADB -> "INSERT IGNORE INTO rt_idempotency";
            } + " (idempotency_key, scope, created_at) VALUES (?, ?, ?)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, key);
                statement.setString(2, scope);
                statement.setLong(3, now.toEpochMilli());
                return statement.executeUpdate() == 1;
            }
        }));
    }

    @Override
    public CompletableFuture<Void> complete(final String key, final String result) {
        Objects.requireNonNull(key, "key");
        return supply(() -> database.write(connection -> {
            final String sql = "UPDATE rt_idempotency SET result = ?, completed_at = ? "
                    + "WHERE idempotency_key = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                setNullableString(statement, 1, result);
                statement.setLong(2, Instant.now().toEpochMilli());
                statement.setString(3, key);
                statement.executeUpdate();
            }
            return (Void) null;
        }));
    }

    @Override
    public CompletableFuture<Optional<IdempotencyRecord>> find(final String key) {
        Objects.requireNonNull(key, "key");
        return supply(() -> database.read(connection -> {
            final String sql = "SELECT idempotency_key, scope, result, created_at, completed_at "
                    + "FROM rt_idempotency WHERE idempotency_key = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, key);
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) {
                        return Optional.<IdempotencyRecord>empty();
                    }
                    final long completedAt = results.getLong("completed_at");
                    return Optional.of(new IdempotencyRecord(
                            results.getString("idempotency_key"),
                            results.getString("scope"),
                            results.getString("result"),
                            Instant.ofEpochMilli(results.getLong("created_at")),
                            results.wasNull() || completedAt == 0L
                                    ? null
                                    : Instant.ofEpochMilli(completedAt)));
                }
            }
        }));
    }

    @Override
    public CompletableFuture<Void> release(final String key) {
        Objects.requireNonNull(key, "key");
        return supply(() -> database.write(connection -> {
            // Only an incomplete claim is released. Deleting a completed key would undo the
            // protection it exists to provide and let the operation run a second time.
            final String sql = "DELETE FROM rt_idempotency WHERE idempotency_key = ? "
                    + "AND completed_at IS NULL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, key);
                statement.executeUpdate();
            }
            return (Void) null;
        }));
    }

    @Override
    public CompletableFuture<Integer> prune(final Instant before) {
        Objects.requireNonNull(before, "before");
        return supply(() -> database.write(connection -> {
            final String sql = "DELETE FROM rt_idempotency WHERE created_at < ? "
                    + "AND completed_at IS NOT NULL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, before.toEpochMilli());
                return statement.executeUpdate();
            }
        }));
    }

    private static void setNullableString(
            final PreparedStatement statement,
            final int index,
            final String value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private <T> CompletableFuture<T> supply(final SqlSupplier<T> supplier) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (final SQLException | RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    /** A JDBC call that may fail, so the executor can turn the failure into a failed future. */
    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
