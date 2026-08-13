package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;
import net.riftbreaker.rifttowny.domain.territory.SpawnPoint;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Town spawn SQL, bound to one connection. */
final class ConnectionSpawnStore implements CivicTransaction.SpawnStore {

    private static final String COLUMNS =
            "town_id, world_id, x, y, z, yaw, pitch, set_by, set_at";

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionSpawnStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public Optional<SpawnPoint> of(final TownId town) {
        Objects.requireNonNull(town, "town");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_town_spawn WHERE town_id = ?")) {
                statement.setString(1, town.value().toString());
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? Optional.of(read(results)) : Optional.<SpawnPoint>empty();
                }
            }
        });
    }

    @Override
    public Map<TownId, SpawnPoint> all() {
        return StorageFailure.wrapping(() -> {
            final Map<TownId, SpawnPoint> loaded = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_town_spawn");
                 ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    loaded.put(TownId.parse(results.getString("town_id")), read(results));
                }
            }
            return Map.copyOf(loaded);
        });
    }

    @Override
    public void set(
            final TownId town, final SpawnPoint spawn, final ResidentId setBy, final Instant now) {
        Objects.requireNonNull(town, "town");
        Objects.requireNonNull(spawn, "spawn");
        Objects.requireNonNull(now, "now");
        StorageFailure.wrapping(() -> {
            // Upsert: a town has one spawn, and moving it is the ordinary thing to do. Delete then
            // insert would leave a window with none, and a failure between the two would lose it.
            final String sql = switch (backend) {
                case SQLITE -> "INSERT INTO rt_town_spawn (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(town_id) DO UPDATE SET world_id = excluded.world_id, "
                        + "x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, "
                        + "pitch = excluded.pitch, set_by = excluded.set_by, "
                        + "set_at = excluded.set_at";
                case MARIADB -> "INSERT INTO rt_town_spawn (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE world_id = VALUES(world_id), x = VALUES(x), "
                        + "y = VALUES(y), z = VALUES(z), yaw = VALUES(yaw), pitch = VALUES(pitch), "
                        + "set_by = VALUES(set_by), set_at = VALUES(set_at)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, town.value().toString());
                statement.setString(2, spawn.worldId().toString());
                statement.setDouble(3, spawn.x());
                statement.setDouble(4, spawn.y());
                statement.setDouble(5, spawn.z());
                statement.setFloat(6, spawn.yaw());
                statement.setFloat(7, spawn.pitch());
                if (setBy == null) {
                    statement.setNull(8, Types.VARCHAR);
                } else {
                    statement.setString(8, setBy.value().toString());
                }
                statement.setLong(9, now.toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean clear(final TownId town) {
        Objects.requireNonNull(town, "town");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_town_spawn WHERE town_id = ?")) {
                statement.setString(1, town.value().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static SpawnPoint read(final ResultSet results) throws SQLException {
        return new SpawnPoint(
                UUID.fromString(results.getString("world_id")),
                results.getDouble("x"),
                results.getDouble("y"),
                results.getDouble("z"),
                results.getFloat("yaw"),
                results.getFloat("pitch"));
    }
}
