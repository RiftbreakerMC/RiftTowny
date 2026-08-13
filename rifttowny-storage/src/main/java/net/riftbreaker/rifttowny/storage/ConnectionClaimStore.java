package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Claim SQL, bound to one connection.
 *
 * <p>Chunk-at-a-time. The unique constraint on {@code (world_id, chunk_x, chunk_z)} is what
 * actually stops two towns owning one chunk; {@link #at} exists so the service can turn that into a
 * sentence instead of a constraint violation, not to replace it.</p>
 */
final class ConnectionClaimStore implements CivicTransaction.ClaimStore {

    private static final String COLUMNS =
            "claim_id, world_id, chunk_x, chunk_z, town_id, claim_kind, claimed_at, "
                    + "plot_type, owner_id";

    private final Connection connection;

    ConnectionClaimStore(final Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public Optional<Claim> at(final ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_claim "
                            + "WHERE world_id = ? AND chunk_x = ? AND chunk_z = ?")) {
                statement.setString(1, chunk.worldId().toString());
                statement.setInt(2, chunk.chunkX());
                statement.setInt(3, chunk.chunkZ());
                final List<Claim> rows = readAll(statement);
                return rows.isEmpty() ? Optional.<Claim>empty() : Optional.of(rows.getFirst());
            }
        });
    }

    @Override
    public List<Claim> of(final TownId town) {
        Objects.requireNonNull(town, "town");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_claim WHERE town_id = ? "
                            + "ORDER BY claimed_at, claim_id")) {
                statement.setString(1, town.value().toString());
                return readAll(statement);
            }
        });
    }

    @Override
    public List<Claim> all() {
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_claim ORDER BY claimed_at, claim_id")) {
                return readAll(statement);
            }
        });
    }

    @Override
    public void insert(final Claim claim) {
        Objects.requireNonNull(claim, "claim");
        StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO rt_claim (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, claim.id().toString());
                statement.setString(2, claim.chunk().worldId().toString());
                statement.setInt(3, claim.chunk().chunkX());
                statement.setInt(4, claim.chunk().chunkZ());
                statement.setString(5, claim.town().value().toString());
                statement.setString(6, claim.kind().storageValue());
                statement.setLong(7, claim.claimedAt().toEpochMilli());
                statement.setString(8, claim.type().storageValue());
                if (claim.owner() == null) {
                    statement.setNull(9, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(9, claim.owner().value().toString());
                }
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean delete(final ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_claim WHERE world_id = ? AND chunk_x = ? AND chunk_z = ?")) {
                statement.setString(1, chunk.worldId().toString());
                statement.setInt(2, chunk.chunkX());
                statement.setInt(3, chunk.chunkZ());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public void updateKind(final ChunkKey chunk, final ClaimKind kind) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(kind, "kind");
        StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE rt_claim SET claim_kind = ? "
                            + "WHERE world_id = ? AND chunk_x = ? AND chunk_z = ?")) {
                statement.setString(1, kind.storageValue());
                statement.setString(2, chunk.worldId().toString());
                statement.setInt(3, chunk.chunkX());
                statement.setInt(4, chunk.chunkZ());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public int deleteAllOf(final TownId town) {
        Objects.requireNonNull(town, "town");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM rt_claim WHERE town_id = ?")) {
                statement.setString(1, town.value().toString());
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Updates what a plot is for and who holds it.
     *
     * <p>Separate from {@link #updateKind}: a plot's use and the town's shape are different facts
     * about the chunk, and one statement that wrote both would let a plot type change quietly move
     * the homeblock.</p>
     */
    @Override
    public void updatePlot(final Claim claim) {
        Objects.requireNonNull(claim, "claim");
        StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE rt_claim SET plot_type = ?, owner_id = ? "
                            + "WHERE world_id = ? AND chunk_x = ? AND chunk_z = ?")) {
                statement.setString(1, claim.type().storageValue());
                if (claim.owner() == null) {
                    statement.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(2, claim.owner().value().toString());
                }
                statement.setString(3, claim.chunk().worldId().toString());
                statement.setInt(4, claim.chunk().chunkX());
                statement.setInt(5, claim.chunk().chunkZ());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Claim> heldBy(final net.riftbreaker.rifttowny.domain.org.ResidentId owner) {
        Objects.requireNonNull(owner, "owner");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_claim WHERE owner_id = ? "
                            + "ORDER BY claimed_at, claim_id")) {
                statement.setString(1, owner.value().toString());
                return readAll(statement);
            }
        });
    }

    @Override
    public int releaseAllHeldBy(
            final net.riftbreaker.rifttowny.domain.org.ResidentId owner, final TownId town) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(town, "town");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE rt_claim SET owner_id = NULL WHERE owner_id = ? AND town_id = ?")) {
                statement.setString(1, owner.value().toString());
                statement.setString(2, town.value().toString());
                return statement.executeUpdate();
            }
        });
    }

    private static net.riftbreaker.rifttowny.domain.org.ResidentId owner(final String raw) {
        return raw == null ? null : net.riftbreaker.rifttowny.domain.org.ResidentId.parse(raw);
    }

    private static List<Claim> readAll(final PreparedStatement statement) throws SQLException {
        final List<Claim> claims = new ArrayList<>();
        try (ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                claims.add(new Claim(
                        UUID.fromString(results.getString("claim_id")),
                        new ChunkKey(
                                UUID.fromString(results.getString("world_id")),
                                results.getInt("chunk_x"),
                                results.getInt("chunk_z")),
                        TownId.parse(results.getString("town_id")),
                        ClaimKind.fromStorage(results.getString("claim_kind")),
                        net.riftbreaker.rifttowny.domain.territory.PlotType.fromStorage(
                                results.getString("plot_type")),
                        owner(results.getString("owner_id")),
                        Instant.ofEpochMilli(results.getLong("claimed_at"))));
            }
        }
        return List.copyOf(claims);
    }
}
