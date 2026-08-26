package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.resident.NoticePreference;
import net.riftbreaker.rifttowny.domain.resident.ResidentPreferences;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Player preference SQL, bound to one connection. */
final class ConnectionPreferenceStore implements CivicTransaction.PreferenceStore {

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionPreferenceStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public void save(final ResidentPreferences.Choice choice, final Instant when) {
        Objects.requireNonNull(choice, "choice");
        Objects.requireNonNull(when, "when");
        StorageFailure.wrapping(() -> {
            final String sql = switch (backend) {
                case SQLITE -> "INSERT INTO rt_resident_preference "
                        + "(resident_id, territory_notice, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (resident_id) DO UPDATE SET "
                        + "territory_notice = excluded.territory_notice, "
                        + "updated_at = excluded.updated_at";
                case MARIADB -> "INSERT INTO rt_resident_preference "
                        + "(resident_id, territory_notice, updated_at) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE territory_notice = VALUES(territory_notice), "
                        + "updated_at = VALUES(updated_at)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, choice.who().value().toString());
                if (choice.notice() == null) {
                    statement.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(2, choice.notice().name());
                }
                statement.setLong(3, when.toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean clear(final ResidentId who) {
        Objects.requireNonNull(who, "who");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_resident_preference WHERE resident_id = ?")) {
                statement.setString(1, who.value().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public Optional<ResidentPreferences.Choice> find(final ResidentId who) {
        Objects.requireNonNull(who, "who");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT resident_id, territory_notice FROM rt_resident_preference "
                            + "WHERE resident_id = ?")) {
                statement.setString(1, who.value().toString());
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? read(results) : Optional.<ResidentPreferences.Choice>empty();
                }
            }
        });
    }

    @Override
    public List<ResidentPreferences.Choice> all() {
        return StorageFailure.wrapping(() -> {
            final List<ResidentPreferences.Choice> found = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT resident_id, territory_notice FROM rt_resident_preference "
                            + "ORDER BY resident_id");
                    ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    read(results).ifPresent(found::add);
                }
            }
            return List.copyOf(found);
        });
    }

    /**
     * One row, or nothing when it cannot be read.
     *
     * <p>Skipped rather than thrown, like every other loader here. A preference written by a later
     * version that this one does not know is a player on the server default, which is the same place
     * they would have been before they chose.</p>
     */
    private static Optional<ResidentPreferences.Choice> read(final ResultSet results)
            throws SQLException {
        try {
            return Optional.of(new ResidentPreferences.Choice(
                    ResidentId.parse(results.getString("resident_id")),
                    NoticePreference.parse(results.getString("territory_notice")).orElse(null)));
        } catch (final IllegalArgumentException | NullPointerException unreadable) {
            return Optional.empty();
        }
    }
}
