package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Town SQL, bound to one connection.
 *
 * <p>A town's residents come from {@code rt_resident.town_id} and are never written here: that
 * column is the single source of truth for membership, and writing it from both sides would create
 * two places to disagree. {@link #save} writes the town row and reconciles trust, nothing else.</p>
 */
final class ConnectionTownStore implements CivicTransaction.TownStore {

    private static final String COLUMNS =
            "town_id, name, name_normalised, nation_id, leader_id, bank_account_id, created_at, "
                    + "board, tag, map_colour, neutral, is_open, public_spawn, resident_tax";

    /** One {@code ?} per column in {@link #COLUMNS}, so the two cannot drift apart by hand. */
    private static final String PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionTownStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public Optional<Town> find(final TownId id) {
        Objects.requireNonNull(id, "id");
        return StorageFailure.wrapping(() ->
                first(load("WHERE town_id = ?", id.value().toString())));
    }

    @Override
    public Optional<Town> findByName(final String name) {
        Objects.requireNonNull(name, "name");
        return StorageFailure.wrapping(() ->
                first(load("WHERE name_normalised = ?", name.toLowerCase(Locale.ROOT))));
    }

    @Override
    public List<Town> all() {
        // One query for the town rows and two more per town for its residents and trust. That is an
        // N+1, and deliberate: it runs once at startup, it reuses the same row-to-aggregate path as
        // every other lookup, and a hand-rolled three-way join here would be a second place for the
        // restore rules to drift from find().
        return StorageFailure.wrapping(() -> load("ORDER BY created_at, town_id"));
    }

    List<Town> findByNation(final NationId nation) {
        Objects.requireNonNull(nation, "nation");
        return StorageFailure.wrapping(() ->
                load("WHERE nation_id = ? ORDER BY created_at, town_id", nation.value().toString()));
    }

    int count() {
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT COUNT(*) FROM rt_town");
                 ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        });
    }

    @Override
    public void save(final Town town) {
        Objects.requireNonNull(town, "town");
        StorageFailure.wrapping(() -> {
            // bank_account_id and created_at are absent from both update clauses on purpose. The
            // civic account must survive every rename and leadership transfer, and letting a save
            // move it is precisely how a treasury gets orphaned.
            final String sql = switch (backend) {
                case SQLITE -> "INSERT INTO rt_town (" + COLUMNS + ") VALUES (" + PLACEHOLDERS + ") "
                        + "ON CONFLICT(town_id) DO UPDATE SET name = excluded.name, "
                        + "name_normalised = excluded.name_normalised, nation_id = excluded.nation_id, "
                        + "leader_id = excluded.leader_id, board = excluded.board, "
                        + "tag = excluded.tag, map_colour = excluded.map_colour, "
                        + "neutral = excluded.neutral, is_open = excluded.is_open, "
                        + "public_spawn = excluded.public_spawn, "
                        + "resident_tax = excluded.resident_tax";
                case MARIADB -> "INSERT INTO rt_town (" + COLUMNS + ") VALUES (" + PLACEHOLDERS + ") "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name), "
                        + "name_normalised = VALUES(name_normalised), nation_id = VALUES(nation_id), "
                        + "leader_id = VALUES(leader_id), board = VALUES(board), "
                        + "tag = VALUES(tag), map_colour = VALUES(map_colour), "
                        + "neutral = VALUES(neutral), is_open = VALUES(is_open), "
                        + "public_spawn = VALUES(public_spawn), "
                        + "resident_tax = VALUES(resident_tax)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, town.id().value().toString());
                statement.setString(2, town.name().display());
                statement.setString(3, town.name().normalised());
                final Optional<NationId> nation = town.nation();
                if (nation.isPresent()) {
                    statement.setString(4, nation.get().value().toString());
                } else {
                    statement.setNull(4, Types.VARCHAR);
                }
                statement.setString(5, town.mayor().value().toString());
                statement.setString(6, town.bankAccountId().toString());
                statement.setLong(7, town.createdAt().toEpochMilli());

                final net.riftbreaker.rifttowny.domain.org.TownProfile profile = town.profile();
                // Empty text is stored as NULL rather than as "". Both read back as "no board", and
                // one of them makes "WHERE board IS NOT NULL" mean what it looks like it means.
                setTextOrNull(statement, 8, profile.board());
                setTextOrNull(statement, 9, profile.tag());
                setTextOrNull(statement, 10, profile.colourForStorage());
                statement.setBoolean(11, profile.neutral());
                statement.setBoolean(12, profile.open());
                statement.setBoolean(13, profile.publicSpawn());
                setTextOrNull(statement, 14, profile.residentTaxForStorage());
                statement.executeUpdate();
            }
            replaceTrust(town);
            return null;
        });
    }

    @Override
    public boolean delete(final TownId id) {
        Objects.requireNonNull(id, "id");
        return StorageFailure.wrapping(() -> {
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
        });
    }

    private static Optional<Town> first(final List<Town> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Writes text, or NULL when it is empty, so "nothing set" has one representation. */
    private static void setTextOrNull(
            final PreparedStatement statement, final int index, final String value)
            throws SQLException {
        if (value == null || value.isEmpty()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private List<Town> load(final String where, final String... parameters) throws SQLException {
        record Row(TownId id, OrganisationName name, ResidentId mayor, NationId nation,
                   UUID bankAccountId, Instant createdAt,
                   net.riftbreaker.rifttowny.domain.org.TownProfile profile) {
        }

        final List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT " + COLUMNS + " FROM rt_town " + where)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    final String nationId = results.getString("nation_id");
                    rows.add(new Row(
                            TownId.parse(results.getString("town_id")),
                            new OrganisationName(
                                    results.getString("name"),
                                    results.getString("name_normalised"),
                                    NamePolicy.skeleton(results.getString("name_normalised"))),
                            ResidentId.parse(results.getString("leader_id")),
                            nationId == null ? null : NationId.parse(nationId),
                            UUID.fromString(results.getString("bank_account_id")),
                            Instant.ofEpochMilli(results.getLong("created_at")),
                            net.riftbreaker.rifttowny.domain.org.TownProfile.restore(
                                    results.getString("board"),
                                    results.getString("tag"),
                                    results.getString("map_colour"),
                                    results.getBoolean("neutral"),
                                    results.getBoolean("is_open"),
                                    results.getBoolean("public_spawn"),
                                    results.getString("resident_tax"))));
                }
            }
        }

        final List<Town> towns = new ArrayList<>(rows.size());
        for (final Row row : rows) {
            towns.add(Town.restore(
                    row.id(), row.name(), row.mayor(), row.nation(), row.bankAccountId(),
                    residentsOf(row.id()), trustedBy(row.id()), row.profile(), row.createdAt()));
        }
        return List.copyOf(towns);
    }

    private Set<ResidentId> residentsOf(final TownId town) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT resident_id FROM rt_resident WHERE town_id = ? "
                        + "ORDER BY joined_at, resident_id")) {
            statement.setString(1, town.value().toString());
            return readIds(statement);
        }
    }

    private Set<ResidentId> trustedBy(final TownId town) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT resident_id FROM rt_town_trust WHERE town_id = ? "
                        + "ORDER BY granted_at, resident_id")) {
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
     * <p>Delete-then-insert rather than a diff: the set is small, the whole save is one transaction,
     * and a diff would need to know which entries changed — information the aggregate does not
     * carry, since it exposes a state rather than a changelog.</p>
     */
    private void replaceTrust(final Town town) throws SQLException {
        // Existing timestamps are read first and re-used. Stamping every row with now would rewrite
        // the whole grant history on an unrelated save - a rename would move both entries to the
        // same instant, and since trustedBy sorts by granted_at, the list would silently reorder
        // into UUID order. Only genuinely new entries get the current time.
        final Map<ResidentId, Long> existing = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT resident_id, granted_at FROM rt_town_trust WHERE town_id = ?")) {
            statement.setString(1, town.id().value().toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    existing.put(
                            ResidentId.parse(results.getString("resident_id")),
                            results.getLong("granted_at"));
                }
            }
        }

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
                statement.setLong(3, existing.getOrDefault(trusted, now));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
