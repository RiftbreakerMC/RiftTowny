package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.NationRepository;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** JDBC nations, working identically on MariaDB and SQLite. */
public final class JdbcNationRepository implements NationRepository {

    private static final String COLUMNS =
            "nation_id, name, name_normalised, leader_id, capital_town_id, bank_account_id, created_at";

    private final RiftTownyDatabase database;
    private final Executor executor;

    public JdbcNationRepository(final RiftTownyDatabase database, final Executor executor) {
        this.database = Objects.requireNonNull(database, "database");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<Optional<Nation>> find(final NationId id) {
        Objects.requireNonNull(id, "id");
        return supply(() -> database.read(connection -> {
            final List<Nation> found =
                    load(connection, "WHERE nation_id = ?", id.value().toString());
            return found.isEmpty() ? Optional.<Nation>empty() : Optional.of(found.getFirst());
        }));
    }

    @Override
    public CompletableFuture<Optional<Nation>> findByName(final String name) {
        Objects.requireNonNull(name, "name");
        return supply(() -> database.read(connection -> {
            final List<Nation> found = load(
                    connection, "WHERE name_normalised = ?", name.toLowerCase(Locale.ROOT));
            return found.isEmpty() ? Optional.<Nation>empty() : Optional.of(found.getFirst());
        }));
    }

    @Override
    public CompletableFuture<Nation> save(final Nation nation) {
        Objects.requireNonNull(nation, "nation");
        return supply(() -> database.write(connection -> {
            final String sql = switch (database.backend()) {
                case SQLITE -> "INSERT INTO rt_nation (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(nation_id) DO UPDATE SET name = excluded.name, "
                        + "name_normalised = excluded.name_normalised, leader_id = excluded.leader_id, "
                        + "capital_town_id = excluded.capital_town_id";
                case MARIADB -> "INSERT INTO rt_nation (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name), "
                        + "name_normalised = VALUES(name_normalised), leader_id = VALUES(leader_id), "
                        + "capital_town_id = VALUES(capital_town_id)";
            };
            // As with towns, bank_account_id and created_at never move on update: the civic account
            // has to survive renames, leadership transfers and capital moves.
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, nation.id().value().toString());
                statement.setString(2, nation.name().display());
                statement.setString(3, nation.name().normalised());
                statement.setString(4, nation.leader().value().toString());
                statement.setString(5, nation.capital().value().toString());
                statement.setString(6, nation.bankAccountId().toString());
                statement.setLong(7, nation.createdAt().toEpochMilli());
                statement.executeUpdate();
            }
            return nation;
        }));
    }

    @Override
    public CompletableFuture<Boolean> delete(final NationId id) {
        Objects.requireNonNull(id, "id");
        return supply(() -> database.transaction(connection -> {
            // Towns are released, not cascaded: a town outlives its nation, and a cascade here
            // would delete towns because their nation dissolved.
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
        }));
    }

    @Override
    public CompletableFuture<Integer> count() {
        return supply(() -> database.read(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT COUNT(*) FROM rt_nation");
                 ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }));
    }

    @Override
    public CompletableFuture<List<Nation>> all() {
        return supply(() -> database.read(connection -> load(connection, "ORDER BY created_at, nation_id")));
    }

    private static List<Nation> load(
            final Connection connection, final String where, final String... parameters)
            throws SQLException {
        record Row(
                NationId id, OrganisationName name, ResidentId leader, TownId capital,
                UUID bankAccountId, Instant createdAt) {
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
                            Instant.ofEpochMilli(results.getLong("created_at"))));
                }
            }
        }

        final List<Nation> nations = new ArrayList<>(rows.size());
        for (final Row row : rows) {
            nations.add(Nation.restore(
                    row.id(), row.name(), row.leader(), row.capital(), row.bankAccountId(),
                    townsOf(connection, row.id()), row.createdAt()));
        }
        return List.copyOf(nations);
    }

    /** A nation's towns live on {@code rt_town.nation_id}, which is the only record of allegiance. */
    private static Set<TownId> townsOf(final Connection connection, final NationId nation)
            throws SQLException {
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
