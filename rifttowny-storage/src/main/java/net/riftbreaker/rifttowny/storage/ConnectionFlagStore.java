package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.flag.FlagOverride;
import net.riftbreaker.rifttowny.domain.flag.FlagTarget;
import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.flag.Relationship;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Flag override SQL, bound to one connection.
 *
 * <p>One row per opinion, keyed on {@code (scope, target, flag, relationship)}. An unreadable row —
 * a flag or relationship removed in a later version, a hand-edited target — is skipped rather than
 * fatal: a layer with one fewer opinion is a state the resolver already handles, and refusing to
 * load the whole set would take every town's protection settings with it.</p>
 */
final class ConnectionFlagStore implements CivicTransaction.FlagStore {

    private static final String COLUMNS =
            "scope, target, flag, relationship, allowed, set_by, set_at";

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionFlagStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public List<FlagOverride> all() {
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_flag_override "
                            + "ORDER BY scope, target, flag, relationship")) {
                return readAll(statement);
            }
        });
    }

    @Override
    public List<FlagOverride> of(final FlagTarget target) {
        Objects.requireNonNull(target, "target");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_flag_override "
                            + "WHERE scope = ? AND target = ? ORDER BY flag, relationship")) {
                statement.setString(1, target.source().name());
                statement.setString(2, target.key());
                return readAll(statement);
            }
        });
    }

    @Override
    public void set(final FlagOverride override) {
        Objects.requireNonNull(override, "override");
        StorageFailure.wrapping(() -> {
            // Upsert rather than delete-then-insert: setting a flag twice is an ordinary thing to
            // do, and a delete that committed without its insert would silently drop the opinion.
            final String sql = switch (backend) {
                case SQLITE -> "INSERT INTO rt_flag_override (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(scope, target, flag, relationship) DO UPDATE SET "
                        + "allowed = excluded.allowed, set_by = excluded.set_by, "
                        + "set_at = excluded.set_at";
                case MARIADB -> "INSERT INTO rt_flag_override (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE allowed = VALUES(allowed), "
                        + "set_by = VALUES(set_by), set_at = VALUES(set_at)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, override.target().source().name());
                statement.setString(2, override.target().key());
                statement.setString(3, override.flag().name());
                statement.setString(4, override.relationship().name());
                statement.setInt(5, override.allowed() ? 1 : 0);
                if (override.setBy() == null) {
                    statement.setNull(6, Types.VARCHAR);
                } else {
                    statement.setString(6, override.setBy().value().toString());
                }
                statement.setLong(7, override.setAt().toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean clear(
            final FlagTarget target, final ProtectionFlag flag, final Relationship relationship) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(relationship, "relationship");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_flag_override "
                            + "WHERE scope = ? AND target = ? AND flag = ? AND relationship = ?")) {
                statement.setString(1, target.source().name());
                statement.setString(2, target.key());
                statement.setString(3, flag.name());
                statement.setString(4, relationship.name());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public int clearAll(final FlagTarget target) {
        Objects.requireNonNull(target, "target");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_flag_override WHERE scope = ? AND target = ?")) {
                statement.setString(1, target.source().name());
                statement.setString(2, target.key());
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Removes every override belonging to a town: its own, and its claims'.
     *
     * <p>Not reachable through the interface, because it is a sweep rather than an edit. Called when
     * a town is disbanded, where the alternative is rows that outlive their owner and reappear if a
     * chunk is ever reclaimed by somebody else.</p>
     */
    int clearForTownAndItsClaims(final java.util.UUID townId, final List<String> claimKeys) {
        return StorageFailure.wrapping(() -> {
            int removed = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_flag_override WHERE scope = 'ORGANISATION' AND target = ?")) {
                statement.setString(1, townId.toString());
                removed += statement.executeUpdate();
            }
            if (claimKeys.isEmpty()) {
                return removed;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_flag_override WHERE scope = 'CLAIM' AND target = ?")) {
                for (final String key : claimKeys) {
                    statement.setString(1, key);
                    statement.addBatch();
                }
                for (final int count : statement.executeBatch()) {
                    removed += Math.max(0, count);
                }
            }
            return removed;
        });
    }

    private static List<FlagOverride> readAll(final PreparedStatement statement)
            throws SQLException {
        final List<FlagOverride> rows = new ArrayList<>();
        try (ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                read(results).ifPresent(rows::add);
            }
        }
        return List.copyOf(rows);
    }

    private static java.util.Optional<FlagOverride> read(final ResultSet results)
            throws SQLException {
        final java.util.Optional<FlagTarget> target =
                FlagTarget.restore(results.getString("scope"), results.getString("target"));
        final java.util.Optional<ProtectionFlag> flag =
                ProtectionFlag.parse(results.getString("flag"));
        final java.util.Optional<Relationship> relationship =
                Relationship.parse(results.getString("relationship"));
        if (target.isEmpty() || flag.isEmpty() || relationship.isEmpty()
                || !target.get().source().isConfigurable()) {
            return java.util.Optional.empty();
        }

        final String setBy = results.getString("set_by");
        return java.util.Optional.of(new FlagOverride(
                target.get(),
                flag.get(),
                relationship.get(),
                results.getInt("allowed") != 0,
                setBy == null ? null : ResidentId.parse(setBy),
                Instant.ofEpochMilli(results.getLong("set_at"))));
    }

}
