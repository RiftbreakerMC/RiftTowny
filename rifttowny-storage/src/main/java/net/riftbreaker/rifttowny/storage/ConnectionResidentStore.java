package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.sql.Connection;
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

/**
 * Resident SQL, bound to one connection.
 *
 * <p>This is where the statements actually live. {@link JdbcResidentRepository} runs them on a
 * connection of its own for single-operation calls, and {@link JdbcCivicTransaction} runs the same
 * ones on a shared connection when a service is changing several aggregates at once. One copy of
 * the SQL, two ways to reach it — the alternative was two copies that drift.</p>
 *
 * <p>Checked {@link SQLException}s are wrapped: the {@link CivicTransaction.ResidentStore} contract
 * is synchronous and unchecked so a service reads as domain logic, and the store runner turns the
 * wrapper back into a failed future.</p>
 */
final class ConnectionResidentStore implements CivicTransaction.ResidentStore {

    private static final String COLUMNS =
            "resident_id, last_known_name, town_id, joined_at, last_seen_at";

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionResidentStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public Optional<Resident> find(final ResidentId id) {
        Objects.requireNonNull(id, "id");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_resident WHERE resident_id = ?")) {
                statement.setString(1, id.value().toString());
                return first(readAll(statement));
            }
        });
    }

    @Override
    public Optional<Resident> findByName(final String name) {
        Objects.requireNonNull(name, "name");
        return StorageFailure.wrapping(() -> {
            // Lowered on both sides rather than relying on the column collation: SQLite compares
            // TEXT case-sensitively by default while MariaDB's utf8mb4_unicode_ci does not.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_resident WHERE LOWER(last_known_name) = ?")) {
                statement.setString(1, name.toLowerCase(Locale.ROOT));
                return first(readAll(statement));
            }
        });
    }

    @Override
    public List<Resident> findByTown(final TownId town) {
        Objects.requireNonNull(town, "town");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_resident WHERE town_id = ? "
                            + "ORDER BY joined_at, resident_id")) {
                statement.setString(1, town.value().toString());
                return readAll(statement);
            }
        });
    }

    @Override
    public int countByTown(final TownId town) {
        Objects.requireNonNull(town, "town");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM rt_resident WHERE town_id = ?")) {
                statement.setString(1, town.value().toString());
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? results.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public java.util.Map<net.riftbreaker.rifttowny.domain.org.ResidentId, String>
            namesOfTownMembers() {
        return StorageFailure.wrapping(() -> {
            final java.util.Map<net.riftbreaker.rifttowny.domain.org.ResidentId, String> names =
                    new java.util.LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT resident_id, last_known_name FROM rt_resident "
                            + "WHERE town_id IS NOT NULL");
                 ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    names.put(
                            net.riftbreaker.rifttowny.domain.org.ResidentId.parse(
                                    results.getString("resident_id")),
                            results.getString("last_known_name"));
                }
            }
            return java.util.Map.copyOf(names);
        });
    }

    @Override
    public void save(final Resident resident) {
        Objects.requireNonNull(resident, "resident");
        StorageFailure.wrapping(() -> {
            // Upsert rather than select-then-insert: two backends saving the same player on join
            // must produce one row, and a check-then-act cannot guarantee that.
            //
            // joined_at is absent from both update clauses on purpose. It records when the player
            // was first seen, and letting a later save move it would quietly rewrite history.
            final String sql = switch (backend) {
                case SQLITE -> "INSERT INTO rt_resident (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(resident_id) DO UPDATE SET "
                        + "last_known_name = excluded.last_known_name, "
                        + "town_id = excluded.town_id, last_seen_at = excluded.last_seen_at";
                case MARIADB -> "INSERT INTO rt_resident (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE last_known_name = VALUES(last_known_name), "
                        + "town_id = VALUES(town_id), last_seen_at = VALUES(last_seen_at)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, resident.id().value().toString());
                statement.setString(2, resident.lastKnownName());
                final Optional<TownId> town = resident.town();
                if (town.isPresent()) {
                    statement.setString(3, town.get().value().toString());
                } else {
                    statement.setNull(3, Types.VARCHAR);
                }
                statement.setLong(4, resident.joinedAt().toEpochMilli());
                statement.setLong(5, resident.lastSeenAt().toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static Optional<Resident> first(final List<Resident> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
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
}
