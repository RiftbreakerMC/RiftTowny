package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Nation SQL, bound to one connection.
 *
 * <p>A nation's towns come from {@code rt_town.nation_id} and are never written here, for the same
 * reason a town's residents are not written by the town store: one record of allegiance, not two.</p>
 */
final class ConnectionNationStore implements CivicTransaction.NationStore {

    private static final String COLUMNS =
            "nation_id, name, name_normalised, leader_id, capital_town_id, bank_account_id, "
                    + "created_at, board, tag, map_colour, neutral";

    /** One {@code ?} per column in {@link #COLUMNS}, so the two cannot drift apart by hand. */
    private static final String PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionNationStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public Optional<Nation> find(final NationId id) {
        Objects.requireNonNull(id, "id");
        return StorageFailure.wrapping(() ->
                first(load("WHERE nation_id = ?", id.value().toString())));
    }

    @Override
    public Optional<Nation> findByName(final String name) {
        Objects.requireNonNull(name, "name");
        return StorageFailure.wrapping(() ->
                first(load("WHERE name_normalised = ?", name.toLowerCase(Locale.ROOT))));
    }

    @Override
    public List<Nation> all() {
        return StorageFailure.wrapping(() -> load("ORDER BY created_at, nation_id"));
    }

    int count() {
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT COUNT(*) FROM rt_nation");
                 ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        });
    }

    @Override
    public void save(final Nation nation) {
        Objects.requireNonNull(nation, "nation");
        StorageFailure.wrapping(() -> {
            final String sql = switch (backend) {
                case SQLITE -> "INSERT INTO rt_nation (" + COLUMNS + ") VALUES (" + PLACEHOLDERS + ") "
                        + "ON CONFLICT(nation_id) DO UPDATE SET name = excluded.name, "
                        + "name_normalised = excluded.name_normalised, leader_id = excluded.leader_id, "
                        + "capital_town_id = excluded.capital_town_id, board = excluded.board, "
                        + "tag = excluded.tag, map_colour = excluded.map_colour, "
                        + "neutral = excluded.neutral";
                case MARIADB -> "INSERT INTO rt_nation (" + COLUMNS + ") VALUES (" + PLACEHOLDERS + ") "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name), "
                        + "name_normalised = VALUES(name_normalised), leader_id = VALUES(leader_id), "
                        + "capital_town_id = VALUES(capital_town_id), board = VALUES(board), "
                        + "tag = VALUES(tag), map_colour = VALUES(map_colour), "
                        + "neutral = VALUES(neutral)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, nation.id().value().toString());
                statement.setString(2, nation.name().display());
                statement.setString(3, nation.name().normalised());
                statement.setString(4, nation.leader().value().toString());
                statement.setString(5, nation.capital().value().toString());
                statement.setString(6, nation.bankAccountId().toString());
                statement.setLong(7, nation.createdAt().toEpochMilli());

                final net.riftbreaker.rifttowny.domain.org.NationProfile profile = nation.profile();
                setTextOrNull(statement, 8, profile.board());
                setTextOrNull(statement, 9, profile.tag());
                setTextOrNull(statement, 10, profile.colourForStorage());
                statement.setBoolean(11, profile.neutral());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean delete(final NationId id) {
        Objects.requireNonNull(id, "id");
        return StorageFailure.wrapping(() -> {
            // Towns are released, not cascaded: a town outlives its nation.
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE rt_town SET nation_id = NULL WHERE nation_id = ?")) {
                statement.setString(1, id.value().toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM rt_nation WHERE nation_id = ?")) {
                statement.setString(1, id.value().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static Optional<Nation> first(final List<Nation> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Writes text, or NULL when it is empty, so "nothing set" has one representation. */
    private static void setTextOrNull(
            final PreparedStatement statement, final int index, final String value)
            throws SQLException {
        if (value == null || value.isEmpty()) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private List<Nation> load(final String where, final String... parameters) throws SQLException {
        record Row(NationId id, OrganisationName name, ResidentId leader, TownId capital,
                   UUID bankAccountId, Instant createdAt,
                   net.riftbreaker.rifttowny.domain.org.NationProfile profile) {
        }

        final List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT " + COLUMNS + " FROM rt_nation " + where)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new Row(
                            NationId.parse(results.getString("nation_id")),
                            new OrganisationName(
                                    results.getString("name"),
                                    results.getString("name_normalised"),
                                    NamePolicy.skeleton(results.getString("name_normalised"))),
                            ResidentId.parse(results.getString("leader_id")),
                            TownId.parse(results.getString("capital_town_id")),
                            UUID.fromString(results.getString("bank_account_id")),
                            Instant.ofEpochMilli(results.getLong("created_at")),
                            net.riftbreaker.rifttowny.domain.org.NationProfile.restore(
                                    results.getString("board"),
                                    results.getString("tag"),
                                    results.getString("map_colour"),
                                    results.getBoolean("neutral"))));
                }
            }
        }

        final List<Nation> nations = new ArrayList<>(rows.size());
        for (final Row row : rows) {
            nations.add(Nation.restore(
                    row.id(), row.name(), row.leader(), row.capital(), row.bankAccountId(),
                    townsOf(row.id()), row.profile(), row.createdAt()));
        }
        return List.copyOf(nations);
    }

    private Set<TownId> townsOf(final NationId nation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT town_id FROM rt_town WHERE nation_id = ? ORDER BY created_at, town_id")) {
            statement.setString(1, nation.value().toString());
            final Set<TownId> towns = new LinkedHashSet<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    towns.add(TownId.parse(results.getString(1)));
                }
            }
            return towns;
        }
    }
}
