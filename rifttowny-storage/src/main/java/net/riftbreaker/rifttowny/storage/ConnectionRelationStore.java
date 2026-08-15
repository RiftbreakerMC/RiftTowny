package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook;
import net.riftbreaker.rifttowny.domain.diplomacy.Relation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Diplomatic declaration SQL, bound to one connection. */
final class ConnectionRelationStore implements CivicTransaction.RelationStore {

    private static final String COLUMNS = "declarer_id, target_id, relation, declared_at";

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionRelationStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /**
     * Records a declaration, leaving an existing one alone.
     *
     * <p>Ignored rather than upserted on conflict: re-declaring an existing alliance should not
     * move its date. The date is when the two nations became allied, and a mayor re-running the
     * command should not be able to rewrite that.</p>
     */
    @Override
    public void declare(final DiplomacyBook.Declaration declaration, final Instant when) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(when, "when");
        StorageFailure.wrapping(() -> {
            final String sql = switch (backend) {
                case SQLITE -> "INSERT INTO rt_nation_relation (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
                case MARIADB -> "INSERT IGNORE INTO rt_nation_relation (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, declaration);
                statement.setLong(4, when.toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean withdraw(final DiplomacyBook.Declaration declaration) {
        Objects.requireNonNull(declaration, "declaration");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_nation_relation WHERE declarer_id = ? AND target_id = ? "
                            + "AND relation = ?")) {
                bind(statement, declaration);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean holds(final DiplomacyBook.Declaration declaration) {
        Objects.requireNonNull(declaration, "declaration");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM rt_nation_relation WHERE declarer_id = ? AND target_id = ? "
                            + "AND relation = ?")) {
                bind(statement, declaration);
                try (ResultSet results = statement.executeQuery()) {
                    return results.next();
                }
            }
        });
    }

    @Override
    public List<DiplomacyBook.Declaration> all() {
        return StorageFailure.wrapping(() -> load("", new String[0]));
    }

    @Override
    public List<DiplomacyBook.Declaration> involving(final NationId nation) {
        Objects.requireNonNull(nation, "nation");
        final String id = nation.value().toString();
        return StorageFailure.wrapping(() ->
                load("WHERE declarer_id = ? OR target_id = ?", new String[] {id, id}));
    }

    private List<DiplomacyBook.Declaration> load(final String where, final String[] parameters)
            throws SQLException {
        final List<DiplomacyBook.Declaration> declarations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM rt_nation_relation " + where
                        + " ORDER BY declared_at, declarer_id")) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    // A row naming a relation this version does not know is skipped rather than
                    // throwing: a downgrade should cost the feature, not the whole startup load.
                    final var relation = Relation.parse(results.getString("relation"));
                    if (relation.isEmpty()) {
                        continue;
                    }
                    declarations.add(new DiplomacyBook.Declaration(
                            NationId.parse(results.getString("declarer_id")),
                            relation.get(),
                            NationId.parse(results.getString("target_id"))));
                }
            }
        }
        return List.copyOf(declarations);
    }

    private static void bind(
            final PreparedStatement statement, final DiplomacyBook.Declaration declaration)
            throws SQLException {
        statement.setString(1, declaration.declarer().value().toString());
        statement.setString(2, declaration.target().value().toString());
        statement.setString(3, declaration.relation().storageValue());
    }
}
