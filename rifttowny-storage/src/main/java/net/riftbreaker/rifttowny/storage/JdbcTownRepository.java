package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.org.TownRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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

/** JDBC towns, working identically on MariaDB and SQLite. */
public final class JdbcTownRepository implements TownRepository {

    private static final String COLUMNS =
            "town_id, name, name_normalised, nation_id, leader_id, bank_account_id, created_at";

    private final RiftTownyDatabase database;
    private final Executor executor;

    public JdbcTownRepository(final RiftTownyDatabase database, final Executor executor) {
        this.database = Objects.requireNonNull(database, "database");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<Optional<Town>> find(final TownId id) {
        Objects.requireNonNull(id, "id");
        return supply(() -> database.read(connection ->
                loadOne(connection, "WHERE town_id = ?", id.value().toString())));
    }

    @Override
    public CompletableFuture<Optional<Town>> findByName(final String name) {
        Objects.requireNonNull(name, "name");
        return supply(() -> database.read(connection -> loadOne(
                connection, "WHERE name_normalised = ?", name.toLowerCase(Locale.ROOT))));
    }

    @Override
    public CompletableFuture<Town> save(final Town town) {
        Objects.requireNonNull(town, "town");
        // One transaction: the town row and its trust rows are a single fact, and a crash between
        // them would leave a town whose trusted list is a mix of two saves.
        return supply(() -> database.transaction(connection -> {
            final String sql = switch (database.backend()) {
                case SQLITE -> "INSERT INTO rt_town (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(town_id) DO UPDATE SET name = excluded.name, "
                        + "name_normalised = excluded.name_normalised, nation_id = excluded.nation_id, "
                        + "leader_id = excluded.leader_id";
                case MARIADB -> "INSERT INTO rt_town (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name), "
                        + "name_normalised = VALUES(name_normalised), nation_id = VALUES(nation_id), "
                        + "leader_id = VALUES(leader_id)";
            };
            // bank_account_id and created_at are absent from both update clauses on purpose. The
            // civic account must survive every rename and leadership transfer, and letting a save
            // move it is precisely how a treasury gets orphaned.
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, town.id().value().toString());
                statement.setString(2, town.name().display());
                statement.setString(3, town.name().normalised());
                setNullable(statement, 4, town.nation().map(nation -> nation.value().toString()).orElse(null));
                statement.setString(5, town.mayor().value().toString());
                statement.setString(6, town.bankAccountId().toString());
                statement.setLong(7, town.createdAt().toEpochMilli());
                statement.executeUpdate();
            }

            replaceTrust(connection, town);
            return town;
        }));
    }

    @Override
    public CompletableFuture<Boolean> delete(final TownId id) {
        Objects.requireNonNull(id, "id");
        return supply(() -> database.transaction(connection -> {
            // Residents are released rather than cascaded: rt_resident rows are players, and a
            // cascade here would delete a player because their town disbanded.
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE rt_resident SET town_id = NULL WHERE town_id = ?")) {
                statement.setString(1, id.value().toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM rt_town WHERE town_id = ?")) {
                statement.setString(1, id.value().toString());
                return statement.executeUpdate() > 0;
            }
        }));
    }

    @Override
    public CompletableFuture<List<Town>> findByNation(final NationId nation) {
        Objects.requireNonNull(nation, "nation");
        return supply(() -> database.read(connection -> {
            final List<TownRow> rows = readRows(
                    connection, "WHERE nation_id = ? ORDER BY created_at, town_id",
                    nation.value().toString());
            final List<Town> towns = new ArrayList<>(rows.size());
            for (final TownRow row : rows) {
                towns.add(hydrate(connection, row));
            }
            return List.copyOf(towns);
        }));
    }

    @Override
    public CompletableFuture<Integer> count() {
        return supply(() -> database.read(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT COUNT(*) FROM rt_town");
                 ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }));
    }

    private Optional<Town> loadOne(
            final Connection connection, final String where, final String parameter)
            throws SQLException {
        final List<TownRow> rows = readRows(connection, where, parameter);
        return rows.isEmpty() ? Optional.empty() : Optional.of(hydrate(connection, rows.getFirst()));
    }

    private static List<TownRow> readRows(
            final Connection connection, final String where, final String parameter)
            throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT " + COLUMNS + " FROM rt_town " + where)) {
            statement.setString(1, parameter);
            final List<TownRow> rows = new ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    final String nationId = results.getString("nation_id");
                    rows.add(new TownRow(
                            TownId.parse(results.getString("town_id")),
                            new OrganisationName(
                                    results.getString("name"),
                                    results.getString("name_normalised"),
                                    NamePolicy.skeleton(results.getString("name_normalised"))),
                            ResidentId.parse(results.getString("leader_id")),
                            nationId == null ? null : NationId.parse(nationId),
                            UUID.fromString(results.getString("bank_account_id")),
                            Instant.ofEpochMilli(results.getLong("created_at"))));
                }
            }
            return rows;
        }
    }

    /** Fills in the parts of a town that live in other tables. */
    private static Town hydrate(final Connection connection, final TownRow row) throws SQLException {
        return Town.restore(
                row.id(), row.name(), row.mayor(), row.nation(), row.bankAccountId(),
                residentsOf(connection, row.id()), trustedBy(connection, row.id()), row.createdAt());
    }

    private static Set<ResidentId> residentsOf(final Connection connection, final TownId town)
            throws SQLException {
        // Ordered by joined_at so the reconstructed set matches the order the aggregate would have
        // built it in, which keeps a GUI listing stable across a restart.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT resident_id FROM rt_resident WHERE town_id = ? ORDER BY joined_at, resident_id")) {
            statement.setString(1, town.value().toString());
            return readIds(statement);
        }
    }

    private static Set<ResidentId> trustedBy(final Connection connection, final TownId town)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT resident_id FROM rt_town_trust WHERE town_id = ? ORDER BY granted_at, resident_id")) {
            statement.setString(1, town.value().toString());
            return readIds(statement);
        }
    }

    private static Set<ResidentId> readIds(final PreparedStatement statement) throws SQLException {
        final Set<ResidentId> ids = new LinkedHashSet<>();
        try (ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                ids.add(ResidentId.parse(results.getString(1)));
            }
        }
        return ids;
    }

    /**
     * Rewrites the trust rows to match the aggregate.
     *
     * <p>Delete-then-insert rather than a diff: the set is small, the whole thing runs in one
     * transaction, and a diff would need to know which entries changed — information the aggregate
     * does not carry, since it exposes a state rather than a changelog.</p>
     */
    private static void replaceTrust(final Connection connection, final Town town) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM rt_town_trust WHERE town_id = ?")) {
            statement.setString(1, town.id().value().toString());
            statement.executeUpdate();
        }
        if (town.trustedOutsiders().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rt_town_trust (town_id, resident_id, granted_at) VALUES (?, ?, ?)")) {
            final long now = Instant.now().toEpochMilli();
            for (final ResidentId trusted : town.trustedOutsiders()) {
                statement.setString(1, town.id().value().toString());
                statement.setString(2, trusted.value().toString());
                statement.setLong(3, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void setNullable(
            final PreparedStatement statement, final int index, final String value)
            throws SQLException {
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

    /** One rt_town row, before the residents and trust from other tables are attached. */
    private record TownRow(
            TownId id,
            OrganisationName name,
            ResidentId mayor,
            NationId nation,
            UUID bankAccountId,
            Instant createdAt
    ) {
    }

    /** A JDBC call that may fail, so the executor can turn the failure into a failed future. */
    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
