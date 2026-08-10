package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.ResidentRepository;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** JDBC residents, working identically on MariaDB and SQLite. */
public final class JdbcResidentRepository implements ResidentRepository {

    private static final String COLUMNS =
            "resident_id, last_known_name, town_id, joined_at, last_seen_at";

    private final RiftTownyDatabase database;
    private final Executor executor;

    public JdbcResidentRepository(final RiftTownyDatabase database, final Executor executor) {
        this.database = Objects.requireNonNull(database, "database");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<Optional<Resident>> find(final ResidentId id) {
        Objects.requireNonNull(id, "id");
        return supply(() -> database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_resident WHERE resident_id = ?")) {
                statement.setString(1, id.value().toString());
                final List<Resident> rows = readAll(statement);
                return rows.isEmpty() ? Optional.<Resident>empty() : Optional.of(rows.getFirst());
            }
        }));
    }

    @Override
    public CompletableFuture<Resident> save(final Resident resident) {
        Objects.requireNonNull(resident, "resident");
        return supply(() -> database.write(connection -> {
            // Upsert rather than select-then-insert: two backends saving the same player on join
            // must produce one row, and a check-then-act cannot guarantee that.
            final String sql = switch (database.backend()) {
                case SQLITE -> "INSERT INTO rt_resident (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(resident_id) DO UPDATE SET last_known_name = excluded.last_known_name, "
                        + "town_id = excluded.town_id, last_seen_at = excluded.last_seen_at";
                case MARIADB -> "INSERT INTO rt_resident (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE last_known_name = VALUES(last_known_name), "
                        + "town_id = VALUES(town_id), last_seen_at = VALUES(last_seen_at)";
            };
            // joined_at is deliberately absent from both update clauses: it records when the player
            // was first seen, and letting a later save move it would quietly rewrite history.
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, resident.id().value().toString());
                statement.setString(2, resident.lastKnownName());
                setNullableUuid(statement, 3, resident.town().map(TownId::value).orElse(null));
                statement.setLong(4, resident.joinedAt().toEpochMilli());
                statement.setLong(5, resident.lastSeenAt().toEpochMilli());
                statement.executeUpdate();
            }
            return resident;
        }));
    }

    @Override
    public CompletableFuture<List<Resident>> findByTown(final TownId town) {
        Objects.requireNonNull(town, "town");
        return supply(() -> database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_resident WHERE town_id = ? ORDER BY joined_at, resident_id")) {
                statement.setString(1, town.value().toString());
                return readAll(statement);
            }
        }));
    }

    @Override
    public CompletableFuture<Integer> countByTown(final TownId town) {
        Objects.requireNonNull(town, "town");
        return supply(() -> database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM rt_resident WHERE town_id = ?")) {
                statement.setString(1, town.value().toString());
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? results.getInt(1) : 0;
                }
            }
        }));
    }

    @Override
    public CompletableFuture<Optional<Resident>> findByName(final String name) {
        Objects.requireNonNull(name, "name");
        return supply(() -> database.read(connection -> {
            // Lowered on both sides rather than relying on the column collation: SQLite compares
            // TEXT case-sensitively by default while MariaDB's utf8mb4_unicode_ci does not, so a
            // bare equality test would behave differently on the two backends.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_resident WHERE LOWER(last_known_name) = ?")) {
                statement.setString(1, name.toLowerCase(Locale.ROOT));
                final List<Resident> rows = readAll(statement);
                return rows.isEmpty() ? Optional.<Resident>empty() : Optional.of(rows.getFirst());
            }
        }));
    }

    private static List<Resident> readAll(final PreparedStatement statement) throws SQLException {
        final List<Resident> residents = new ArrayList<>();
        try (ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                final String townId = results.getString("town_id");
                residents.add(Resident.restore(
                        new ResidentId(UUID.fromString(results.getString("resident_id"))),
                        results.getString("last_known_name"),
                        townId == null ? null : TownId.parse(townId),
                        Instant.ofEpochMilli(results.getLong("joined_at")),
                        Instant.ofEpochMilli(results.getLong("last_seen_at"))));
            }
        }
        return List.copyOf(residents);
    }

    private static void setNullableUuid(
            final PreparedStatement statement, final int index, final UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.toString());
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
