package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
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
 * Role SQL, bound to one connection.
 *
 * <p>A {@link RoleBook} is loaded and saved whole. Its rules are about the set — unique priorities,
 * who outranks whom, which assignments survive a deletion — so a partial write is not a smaller
 * change, it is an inconsistent one. Saving replaces the organisation's rows inside the caller's
 * transaction.</p>
 */
final class ConnectionRoleStore implements CivicTransaction.RoleStore {

    private final Connection connection;

    ConnectionRoleStore(final Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public Optional<RoleBook> find(final OrganisationScope scope, final UUID organisationId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(organisationId, "organisationId");

        return StorageFailure.wrapping(() -> {
            final List<Role> roles = loadRoles(scope, organisationId);
            if (roles.isEmpty()) {
                // No rows at all means the organisation has no role book yet, which is different
                // from a corrupt one. RoleBook.restore would throw on a missing system role, so the
                // emptiness is answered here instead.
                return Optional.<RoleBook>empty();
            }
            return Optional.of(RoleBook.restore(
                    scope, organisationId, roles, loadAssignments(roles)));
        });
    }

    @Override
    public void save(final RoleBook book) {
        Objects.requireNonNull(book, "book");
        StorageFailure.wrapping(() -> {
            // Replace rather than diff. The book exposes a state, not a changelog, so a diff would
            // have to be reconstructed by comparing against a re-read - which is the same number of
            // statements with an extra chance to be wrong.
            // Grant times are read before the wipe and re-used, so an unrelated save - a rename, a
            // permission change - does not rewrite when everybody got their role. Without it every
            // holder's granted_at collapses to the same instant and the holder list reorders.
            final Map<String, Long> grantedAt = loadGrantTimes(book.scope(), book.organisationId());
            deleteExisting(book.scope(), book.organisationId());

            for (final Role role : book.ordered()) {
                insertRole(book, role);
                insertPermissions(role);
            }
            final long now = Instant.now().toEpochMilli();
            for (final Role role : book.ordered()) {
                for (final ResidentId holder : book.holdersOf(role.id())) {
                    final String key = role.id().value() + ":" + holder.value();
                    insertAssignment(role.id(), holder, grantedAt.getOrDefault(key, now));
                }
            }
            return null;
        });
    }

    @Override
    public boolean delete(final OrganisationScope scope, final UUID organisationId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(organisationId, "organisationId");
        return StorageFailure.wrapping(() -> deleteExisting(scope, organisationId) > 0);
    }

    private List<Role> loadRoles(final OrganisationScope scope, final UUID organisationId)
            throws SQLException {
        final String sql = "SELECT role_id, name, display_name, icon, chat_prefix, priority, "
                + "system_type, created_at FROM rt_role WHERE organisation_id = ? AND scope = ? "
                + "ORDER BY priority DESC, name_normalised";
        final List<Role> roles = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, organisationId.toString());
            statement.setString(2, scope.storageValue());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    final RoleId id = RoleId.parse(results.getString("role_id"));
                    roles.add(Role.restore(
                            id, scope, organisationId,
                            results.getString("name"),
                            results.getString("display_name"),
                            results.getString("icon"),
                            results.getString("chat_prefix"),
                            results.getInt("priority"),
                            SystemRole.parse(results.getString("system_type")).orElse(null),
                            loadPermissions(id),
                            Instant.ofEpochMilli(results.getLong("created_at"))));
                }
            }
        }
        return roles;
    }

    private Set<Permission> loadPermissions(final RoleId role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT permission FROM rt_role_permission WHERE role_id = ?")) {
            statement.setString(1, role.value().toString());
            final EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    // Unknown names are skipped rather than fatal: a permission removed in a later
                    // version must not stop every role in the database from loading.
                    Permission.parse(results.getString(1)).ifPresent(permissions::add);
                }
            }
            return permissions;
        }
    }

    private Map<ResidentId, Set<RoleId>> loadAssignments(final List<Role> roles) throws SQLException {
        final Map<ResidentId, Set<RoleId>> assignments = new LinkedHashMap<>();
        if (roles.isEmpty()) {
            return assignments;
        }
        final String placeholders = String.join(", ", java.util.Collections.nCopies(roles.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role_id, resident_id FROM rt_role_member WHERE role_id IN (" + placeholders
                        + ") ORDER BY granted_at, resident_id")) {
            int index = 1;
            for (final Role role : roles) {
                statement.setString(index++, role.id().value().toString());
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    assignments
                            .computeIfAbsent(
                                    ResidentId.parse(results.getString("resident_id")),
                                    ignored -> new LinkedHashSet<>())
                            .add(RoleId.parse(results.getString("role_id")));
                }
            }
        }
        return assignments;
    }

    /**
     * Removes an organisation's roles.
     *
     * <p>Permissions and assignments go with them by foreign key cascade, so only the parent rows
     * are deleted here.</p>
     */
    private int deleteExisting(final OrganisationScope scope, final UUID organisationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM rt_role WHERE organisation_id = ? AND scope = ?")) {
            statement.setString(1, organisationId.toString());
            statement.setString(2, scope.storageValue());
            return statement.executeUpdate();
        }
    }

    private void insertRole(final RoleBook book, final Role role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rt_role (role_id, scope, organisation_id, name, name_normalised, "
                        + "display_name, icon, chat_prefix, priority, created_at, system_type) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, role.id().value().toString());
            statement.setString(2, book.scope().storageValue());
            statement.setString(3, book.organisationId().toString());
            statement.setString(4, role.name());
            statement.setString(5, role.nameNormalised());
            statement.setString(6, role.displayName());
            setNullable(statement, 7, role.icon().orElse(null));
            setNullable(statement, 8, role.chatPrefix().orElse(null));
            statement.setInt(9, role.priority());
            statement.setLong(10, role.createdAt().toEpochMilli());
            setNullable(statement, 11, role.systemRole().map(Enum::name).orElse(null));
            statement.executeUpdate();
        }
    }

    private void insertPermissions(final Role role) throws SQLException {
        if (role.permissions().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rt_role_permission (role_id, permission) VALUES (?, ?)")) {
            for (final Permission permission : role.permissions()) {
                statement.setString(1, role.id().value().toString());
                statement.setString(2, permission.name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Every existing (role, holder) grant time, keyed so a rewrite can preserve it. */
    private Map<String, Long> loadGrantTimes(
            final OrganisationScope scope, final UUID organisationId) throws SQLException {
        final Map<String, Long> times = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT m.role_id, m.resident_id, m.granted_at FROM rt_role_member m "
                        + "JOIN rt_role r ON r.role_id = m.role_id "
                        + "WHERE r.organisation_id = ? AND r.scope = ?")) {
            statement.setString(1, organisationId.toString());
            statement.setString(2, scope.storageValue());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    times.put(
                            results.getString("role_id") + ":" + results.getString("resident_id"),
                            results.getLong("granted_at"));
                }
            }
        }
        return times;
    }

    private void insertAssignment(
            final RoleId role, final ResidentId holder, final long grantedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rt_role_member (role_id, resident_id, granted_at) VALUES (?, ?, ?)")) {
            statement.setString(1, role.value().toString());
            statement.setString(2, holder.value().toString());
            statement.setLong(3, grantedAt);
            statement.executeUpdate();
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

    /** Normalises a stored scope string, tolerant of casing written by an older build. */
    static OrganisationScope scopeOf(final String raw) {
        return OrganisationScope.fromStorage(raw == null ? null : raw.toUpperCase(Locale.ROOT));
    }
}
