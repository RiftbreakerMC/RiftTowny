package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.justice.Outlaws;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Outlaw SQL, bound to one connection. */
final class ConnectionOutlawStore implements CivicTransaction.OutlawStore {

    private static final String COLUMNS = "town_id, resident_id, declared_by, declared_at";

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionOutlawStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /**
     * Records one, leaving an existing row alone.
     *
     * <p>Ignored rather than upserted on conflict, for the same reason a re-declared alliance keeps
     * its date: the date is when the town outlawed them, and an officer re-running the command
     * should not be able to rewrite when a sanction began — that date is what an appeal argues
     * about.</p>
     */
    @Override
    public void declare(
            final TownId town, final ResidentId who, final ResidentId by, final Instant when) {
        Objects.requireNonNull(town, "town");
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(when, "when");
        StorageFailure.wrapping(() -> {
            final String sql = switch (backend) {
                case SQLITE -> "INSERT INTO rt_town_outlaw (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
                case MARIADB -> "INSERT IGNORE INTO rt_town_outlaw (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, town.value().toString());
                statement.setString(2, who.value().toString());
                // Null for the console or an import: the column allows it, and "nobody in
                // particular" is a truer answer than naming whoever happened to be the mayor.
                if (by == null) {
                    statement.setNull(3, java.sql.Types.CHAR);
                } else {
                    statement.setString(3, by.value().toString());
                }
                statement.setLong(4, when.toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean pardon(final TownId town, final ResidentId who) {
        Objects.requireNonNull(town, "town");
        Objects.requireNonNull(who, "who");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_town_outlaw WHERE town_id = ? AND resident_id = ?")) {
                statement.setString(1, town.value().toString());
                statement.setString(2, who.value().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean holds(final TownId town, final ResidentId who) {
        Objects.requireNonNull(town, "town");
        Objects.requireNonNull(who, "who");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM rt_town_outlaw WHERE town_id = ? AND resident_id = ?")) {
                statement.setString(1, town.value().toString());
                statement.setString(2, who.value().toString());
                try (ResultSet results = statement.executeQuery()) {
                    return results.next();
                }
            }
        });
    }

    @Override
    public List<Outlaws.Declaration> all() {
        return StorageFailure.wrapping(() -> {
            final List<Outlaws.Declaration> found = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT town_id, resident_id, declared_by, declared_at FROM rt_town_outlaw "
                            + "ORDER BY town_id, resident_id");
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
     * <p>Skipped rather than thrown, like every other loader here: a hand-edited id must not stop
     * the whole book loading, and a declaration that fails to load is one player who is not barred
     * rather than a server that will not start.</p>
     */
    private static java.util.Optional<Outlaws.Declaration> read(final ResultSet results)
            throws SQLException {
        try {
            final String by = results.getString("declared_by");
            return java.util.Optional.of(new Outlaws.Declaration(
                    TownId.parse(results.getString("town_id")),
                    ResidentId.parse(results.getString("resident_id")),
                    by == null ? null : ResidentId.parse(by),
                    java.time.Instant.ofEpochMilli(results.getLong("declared_at"))));
        } catch (final IllegalArgumentException | NullPointerException unreadable) {
            return java.util.Optional.empty();
        }
    }
}
