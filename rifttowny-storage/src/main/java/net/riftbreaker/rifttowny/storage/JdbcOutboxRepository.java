package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.outbox.OutboxCounts;
import net.riftbreaker.rifttowny.domain.outbox.OutboxEvent;
import net.riftbreaker.rifttowny.domain.outbox.OutboxRepository;
import net.riftbreaker.rifttowny.domain.outbox.OutboxStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** JDBC outbox, working identically on MariaDB and SQLite. */
public final class JdbcOutboxRepository implements OutboxRepository {

    private static final String COLUMNS =
            "id, event_id, event_type, payload, correlation_id, created_at, available_at, "
                    + "attempts, status, claimed_by, last_error";

    private final RiftTownyDatabase database;
    private final Executor executor;
    private final Clock clock;

    public JdbcOutboxRepository(final RiftTownyDatabase database, final Executor executor) {
        this(database, executor, Clock.systemUTC());
    }

    /**
     * @param clock injected so claim expiry can be tested deterministically. Claim staleness is a
     *        comparison between two wall-clock instants, and with the system clock a test would have
     *        to sleep to observe it — which is exactly the kind of test that passes on a laptop and
     *        fails on a loaded CI agent.
     */
    public JdbcOutboxRepository(
            final RiftTownyDatabase database,
            final Executor executor,
            final Clock clock
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletableFuture<OutboxEvent> append(final OutboxEvent event) {
        Objects.requireNonNull(event, "event");
        return supply(() -> database.transaction(connection -> {
            // Insert-ignore rather than select-then-insert: two servers appending the same event id
            // concurrently must produce one row, and a check-then-act cannot guarantee that.
            final String insert = switch (database.backend()) {
                case SQLITE -> "INSERT OR IGNORE INTO rt_outbox";
                case MARIADB -> "INSERT IGNORE INTO rt_outbox";
            } + " (event_id, event_type, payload, correlation_id, created_at, available_at, "
                    + "attempts, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, event.eventId().toString());
                statement.setString(2, event.eventType());
                statement.setString(3, event.payload());
                setNullableString(statement, 4, event.correlationId());
                statement.setLong(5, event.createdAt().toEpochMilli());
                statement.setLong(6, event.availableAt().toEpochMilli());
                statement.setInt(7, event.attempts());
                statement.setString(8, event.status().name());
                statement.executeUpdate();
            }

            return findIn(connection, event.eventId()).orElseThrow(() -> new SQLException(
                    "Outbox row for " + event.eventId() + " vanished immediately after insert"));
        }));
    }

    @Override
    public CompletableFuture<List<OutboxEvent>> claimBatch(
            final String serverId,
            final int limit,
            final Duration claimTimeout
    ) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(claimTimeout, "claimTimeout");
        if (limit < 1) {
            return CompletableFuture.completedFuture(List.of());
        }

        return supply(() -> database.transaction(connection -> {
            final long now = clock.instant().toEpochMilli();
            // <= rather than <, so a zero timeout means "every existing claim is stale" without
            // depending on a millisecond having elapsed since the claim was taken.
            final long staleAtOrBefore = now - Math.max(0L, claimTimeout.toMillis());

            final List<Long> candidates = selectClaimCandidates(connection, now, staleAtOrBefore, limit);
            if (candidates.isEmpty()) {
                return List.of();
            }

            // Compare-and-set: the WHERE clause repeats the eligibility test, so a concurrent
            // claimer that got there first simply causes this update to affect fewer rows rather
            // than two servers both believing they own the event.
            final String update = "UPDATE rt_outbox SET status = ?, claimed_by = ?, claimed_at = ? "
                    + "WHERE id IN (" + placeholders(candidates.size()) + ") "
                    + "AND (status = ? OR (status = ? AND claimed_at IS NOT NULL AND claimed_at <= ?))";

            try (PreparedStatement statement = connection.prepareStatement(update)) {
                int index = 1;
                statement.setString(index++, OutboxStatus.CLAIMED.name());
                statement.setString(index++, serverId);
                statement.setLong(index++, now);
                for (final Long id : candidates) {
                    statement.setLong(index++, id);
                }
                statement.setString(index++, OutboxStatus.PENDING.name());
                statement.setString(index++, OutboxStatus.CLAIMED.name());
                statement.setLong(index, staleAtOrBefore);
                statement.executeUpdate();
            }

            // Read back by the candidate ids, not by (claimed_by, claimed_at, status). Claim
            // metadata is not unique to a call: a dispatcher draining the queue calls this twice
            // inside the same millisecond, both writes stamp the same claimed_at, and a metadata
            // query would return the earlier batch as well - handing the caller more rows than the
            // limit and re-dispatching events it already holds. Discord would see the same town
            // founding announced twice, and markDelivered on an already-delivered event is a no-op,
            // so nothing downstream corrects it.
            final String select = "SELECT " + COLUMNS + " FROM rt_outbox "
                    + "WHERE id IN (" + placeholders(candidates.size()) + ") "
                    + "AND claimed_by = ? AND claimed_at = ? AND status = ? ORDER BY available_at, id";
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                int index = 1;
                for (final Long id : candidates) {
                    statement.setLong(index++, id);
                }
                statement.setString(index++, serverId);
                statement.setLong(index++, now);
                statement.setString(index, OutboxStatus.CLAIMED.name());
                return readAll(statement);
            }
        }));
    }

    @Override
    public CompletableFuture<Void> markDelivered(final UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return run(() -> database.write(connection -> {
            final String sql = "UPDATE rt_outbox SET status = ?, claimed_by = NULL, "
                    + "claimed_at = NULL, last_error = NULL WHERE event_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, OutboxStatus.DELIVERED.name());
                statement.setString(2, eventId.toString());
                statement.executeUpdate();
            }
            return null;
        }));
    }

    @Override
    public CompletableFuture<Void> markFailed(
            final UUID eventId,
            final String error,
            final Instant retryAt,
            final int maxAttempts
    ) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(retryAt, "retryAt");
        return run(() -> database.transaction(connection -> {
            final int attempts = currentAttempts(connection, eventId) + 1;
            // Exhausting the budget parks the row as FAILED instead of deleting it: an announcement
            // that never reached Discord is something an operator has to be able to find.
            final OutboxStatus next = attempts >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING;

            final String sql = "UPDATE rt_outbox SET attempts = ?, status = ?, available_at = ?, "
                    + "last_error = ?, claimed_by = NULL, claimed_at = NULL WHERE event_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, attempts);
                statement.setString(2, next.name());
                statement.setLong(3, retryAt.toEpochMilli());
                setNullableString(statement, 4, error);
                statement.setString(5, eventId.toString());
                statement.executeUpdate();
            }
            return null;
        }));
    }

    @Override
    public CompletableFuture<Optional<OutboxEvent>> find(final UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return supply(() -> database.read(connection -> findIn(connection, eventId)));
    }

    @Override
    public CompletableFuture<Integer> pruneDelivered(final Instant before) {
        Objects.requireNonNull(before, "before");
        return supply(() -> database.write(connection -> {
            final String sql = "DELETE FROM rt_outbox WHERE status = ? AND created_at < ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, OutboxStatus.DELIVERED.name());
                statement.setLong(2, before.toEpochMilli());
                return statement.executeUpdate();
            }
        }));
    }


    @Override
    public CompletableFuture<Integer> pruneUndelivered(final Instant before) {
        Objects.requireNonNull(before, "before");
        return supply(() -> database.write(connection -> {
            final String sql = "DELETE FROM rt_outbox WHERE status = ? AND created_at < ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, OutboxStatus.PENDING.name());
                statement.setLong(2, before.toEpochMilli());
                return statement.executeUpdate();
            }
        }));
    }
    @Override
    public CompletableFuture<OutboxCounts> counts() {
        return supply(() -> database.read(connection -> {
            long pending = 0L;
            long claimed = 0L;
            long delivered = 0L;
            long failed = 0L;
            final String sql = "SELECT status, COUNT(*) AS total FROM rt_outbox GROUP BY status";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    final long total = results.getLong("total");
                    switch (OutboxStatus.valueOf(results.getString("status"))) {
                        case PENDING -> pending = total;
                        case CLAIMED -> claimed = total;
                        case DELIVERED -> delivered = total;
                        case FAILED -> failed = total;
                    }
                }
            }
            return new OutboxCounts(pending, claimed, delivered, failed);
        }));
    }

    private List<Long> selectClaimCandidates(
            final Connection connection,
            final long now,
            final long staleAtOrBefore,
            final int limit
    ) throws SQLException {
        // LIMIT is applied to a SELECT rather than to the UPDATE: MySQL supports UPDATE ... LIMIT
        // but SQLite only does when compiled with SQLITE_ENABLE_UPDATE_DELETE_LIMIT, which is not
        // guaranteed in the bundled build.
        final String sql = "SELECT id FROM rt_outbox WHERE available_at <= ? AND ("
                + "status = ? OR (status = ? AND claimed_at IS NOT NULL AND claimed_at <= ?)) "
                + "ORDER BY available_at, id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setString(2, OutboxStatus.PENDING.name());
            statement.setString(3, OutboxStatus.CLAIMED.name());
            statement.setLong(4, staleAtOrBefore);
            statement.setInt(5, limit);
            final List<Long> ids = new ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    ids.add(results.getLong(1));
                }
            }
            return ids;
        }
    }

    private int currentAttempts(final Connection connection, final UUID eventId) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT attempts FROM rt_outbox WHERE event_id = ?")) {
            statement.setString(1, eventId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private Optional<OutboxEvent> findIn(final Connection connection, final UUID eventId)
            throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT " + COLUMNS + " FROM rt_outbox WHERE event_id = ?")) {
            statement.setString(1, eventId.toString());
            final List<OutboxEvent> rows = readAll(statement);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        }
    }

    private static List<OutboxEvent> readAll(final PreparedStatement statement) throws SQLException {
        final List<OutboxEvent> events = new ArrayList<>();
        try (ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                events.add(new OutboxEvent(
                        results.getLong("id"),
                        UUID.fromString(results.getString("event_id")),
                        results.getString("event_type"),
                        results.getString("payload"),
                        results.getString("correlation_id"),
                        Instant.ofEpochMilli(results.getLong("created_at")),
                        Instant.ofEpochMilli(results.getLong("available_at")),
                        results.getInt("attempts"),
                        OutboxStatus.valueOf(results.getString("status")),
                        results.getString("claimed_by"),
                        results.getString("last_error")));
            }
        }
        return List.copyOf(events);
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

    private static String placeholders(final int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
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

    private CompletableFuture<Void> run(final SqlSupplier<Void> supplier) {
        return supply(supplier);
    }

    /** A JDBC call that may fail, so the executor can turn the failure into a failed future. */
    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
