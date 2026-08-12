package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.org.Invitation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Invitation SQL, bound to one connection.
 *
 * <p>An unreadable row — an inviter scope or invitee kind a later version removed — is skipped
 * rather than fatal. An offer nobody can interpret is an offer nobody can accept, which is the same
 * outcome as it having lapsed.</p>
 */
final class ConnectionInvitationStore implements CivicTransaction.InvitationStore {

    private static final String COLUMNS =
            "invitation_id, inviter_scope, inviter_id, invitee_kind, invitee_id, created_by, "
                    + "created_at, expires_at";

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionInvitationStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public Optional<Invitation> find(
            final OrganisationId inviter, final Invitation.Invitee invitee) {
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(invitee, "invitee");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_invitation WHERE inviter_scope = ? "
                            + "AND inviter_id = ? AND invitee_kind = ? AND invitee_id = ?")) {
                statement.setString(1, inviter.scope().name());
                statement.setString(2, inviter.value().toString());
                statement.setString(3, invitee.kind());
                statement.setString(4, invitee.value().toString());
                final List<Invitation> rows = readAll(statement);
                return rows.isEmpty() ? Optional.<Invitation>empty() : Optional.of(rows.getFirst());
            }
        });
    }

    @Override
    public List<Invitation> to(final Invitation.Invitee invitee) {
        Objects.requireNonNull(invitee, "invitee");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_invitation "
                            + "WHERE invitee_kind = ? AND invitee_id = ? "
                            + "ORDER BY created_at DESC, invitation_id")) {
                statement.setString(1, invitee.kind());
                statement.setString(2, invitee.value().toString());
                return readAll(statement);
            }
        });
    }

    @Override
    public List<Invitation> from(final OrganisationId inviter) {
        Objects.requireNonNull(inviter, "inviter");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_invitation "
                            + "WHERE inviter_scope = ? AND inviter_id = ? "
                            + "ORDER BY created_at DESC, invitation_id")) {
                statement.setString(1, inviter.scope().name());
                statement.setString(2, inviter.value().toString());
                return readAll(statement);
            }
        });
    }

    @Override
    public void save(final Invitation invitation) {
        Objects.requireNonNull(invitation, "invitation");
        StorageFailure.wrapping(() -> {
            // Upserted on the pairing rather than the id: re-inviting is the same offer repeated,
            // and it should refresh the expiry rather than leave two rows racing to be found first.
            final String sql = switch (backend) {
                case SQLITE -> "INSERT INTO rt_invitation (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(inviter_scope, inviter_id, invitee_kind, invitee_id) "
                        + "DO UPDATE SET invitation_id = excluded.invitation_id, "
                        + "created_by = excluded.created_by, created_at = excluded.created_at, "
                        + "expires_at = excluded.expires_at";
                case MARIADB -> "INSERT INTO rt_invitation (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE invitation_id = VALUES(invitation_id), "
                        + "created_by = VALUES(created_by), created_at = VALUES(created_at), "
                        + "expires_at = VALUES(expires_at)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, invitation.id().toString());
                statement.setString(2, invitation.inviter().scope().name());
                statement.setString(3, invitation.inviter().value().toString());
                statement.setString(4, invitation.invitee().kind());
                statement.setString(5, invitation.invitee().value().toString());
                if (invitation.createdBy() == null) {
                    statement.setNull(6, Types.VARCHAR);
                } else {
                    statement.setString(6, invitation.createdBy().value().toString());
                }
                statement.setLong(7, invitation.createdAt().toEpochMilli());
                statement.setLong(8, invitation.expiresAt().toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean delete(final OrganisationId inviter, final Invitation.Invitee invitee) {
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(invitee, "invitee");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_invitation WHERE inviter_scope = ? AND inviter_id = ? "
                            + "AND invitee_kind = ? AND invitee_id = ?")) {
                statement.setString(1, inviter.scope().name());
                statement.setString(2, inviter.value().toString());
                statement.setString(3, invitee.kind());
                statement.setString(4, invitee.value().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public int deleteAllFor(final OrganisationId organisation) {
        Objects.requireNonNull(organisation, "organisation");
        return StorageFailure.wrapping(() -> {
            int removed = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_invitation WHERE inviter_scope = ? AND inviter_id = ?")) {
                statement.setString(1, organisation.scope().name());
                statement.setString(2, organisation.value().toString());
                removed += statement.executeUpdate();
            }
            // Both directions. A disbanded town's outstanding offers are gone, and so are the ones
            // addressed to it - an offer to a town that no longer exists can never be accepted, and
            // leaving it would make it acceptable again if the id were somehow reused.
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_invitation WHERE invitee_kind = ? AND invitee_id = ?")) {
                statement.setString(1, organisation.scope() == OrganisationScope.TOWN
                        ? "TOWN"
                        : "NATION");
                statement.setString(2, organisation.value().toString());
                removed += statement.executeUpdate();
            }
            return removed;
        });
    }

    @Override
    public int deleteExpired(final Instant now) {
        Objects.requireNonNull(now, "now");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_invitation WHERE expires_at <= ?")) {
                statement.setLong(1, now.toEpochMilli());
                return statement.executeUpdate();
            }
        });
    }

    private static List<Invitation> readAll(final PreparedStatement statement) throws SQLException {
        final List<Invitation> rows = new ArrayList<>();
        try (ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                read(results).ifPresent(rows::add);
            }
        }
        return List.copyOf(rows);
    }

    private static Optional<Invitation> read(final ResultSet results) throws SQLException {
        final Optional<OrganisationId> inviter =
                inviter(results.getString("inviter_scope"), results.getString("inviter_id"));
        final Optional<Invitation.Invitee> invitee = Invitation.Invitee.restore(
                results.getString("invitee_kind"), results.getString("invitee_id"));
        if (inviter.isEmpty() || invitee.isEmpty()) {
            return Optional.empty();
        }

        final String createdBy = results.getString("created_by");
        return Optional.of(new Invitation(
                UUID.fromString(results.getString("invitation_id")),
                inviter.get(),
                invitee.get(),
                createdBy == null ? null : ResidentId.parse(createdBy),
                Instant.ofEpochMilli(results.getLong("created_at")),
                Instant.ofEpochMilli(results.getLong("expires_at"))));
    }

    private static Optional<OrganisationId> inviter(final String scope, final String id) {
        if (scope == null || id == null) {
            return Optional.empty();
        }
        try {
            return switch (scope) {
                case "TOWN" -> Optional.of(TownId.parse(id));
                case "NATION" -> Optional.of(NationId.parse(id));
                default -> Optional.empty();
            };
        } catch (final IllegalArgumentException unparseable) {
            return Optional.empty();
        }
    }
}
