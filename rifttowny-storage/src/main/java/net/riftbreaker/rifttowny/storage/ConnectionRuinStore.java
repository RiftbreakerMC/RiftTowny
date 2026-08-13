package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;
import net.riftbreaker.rifttowny.domain.territory.Ruin;

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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Ruin SQL, bound to one connection.
 *
 * <p>The row and its land are written together on a fall and separately afterwards, because they
 * stop being the same thing the moment the ruin lets go: {@link #releaseLand} empties the chunks and
 * leaves the row, which is what {@code RT-MOD-REGEN} and the anti-recreation rule read later.</p>
 */
final class ConnectionRuinStore implements CivicTransaction.RuinStore {

    private static final String COLUMNS =
            "ruin_id, former_town_id, name, founder_id, ruined_at, expires_at, "
                    + "reclaimed_at, reclaimed_by, reclaimed_as";

    private final Connection connection;

    ConnectionRuinStore(final Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public Optional<Ruin> find(final UUID ruinId) {
        Objects.requireNonNull(ruinId, "ruinId");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_ruin WHERE ruin_id = ?")) {
                statement.setString(1, ruinId.toString());
                final List<Ruin> rows = readAll(statement);
                return rows.isEmpty() ? Optional.<Ruin>empty() : Optional.of(rows.getFirst());
            }
        });
    }

    @Override
    public Optional<Ruin> at(final ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT r." + COLUMNS.replace(", ", ", r.") + " FROM rt_ruin r "
                            + "JOIN rt_ruin_claim c ON c.ruin_id = r.ruin_id "
                            + "WHERE c.world_id = ? AND c.chunk_x = ? AND c.chunk_z = ?")) {
                statement.setString(1, chunk.worldId().toString());
                statement.setInt(2, chunk.chunkX());
                statement.setInt(3, chunk.chunkZ());
                final List<Ruin> rows = readAll(statement);
                return rows.isEmpty() ? Optional.<Ruin>empty() : Optional.of(rows.getFirst());
            }
        });
    }

    @Override
    public Map<Ruin, Set<ChunkKey>> standing() {
        return StorageFailure.wrapping(() -> {
            // Only ruins that still hold land. A reclaimed or lapsed row owns nothing, and the join
            // is what expresses that rather than a status column that could disagree with the facts.
            final Map<UUID, Ruin> found = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_ruin WHERE reclaimed_at IS NULL "
                            + "AND ruin_id IN (SELECT ruin_id FROM rt_ruin_claim) "
                            + "ORDER BY ruined_at, ruin_id")) {
                for (final Ruin ruin : readAll(statement)) {
                    found.put(ruin.id(), ruin);
                }
            }
            if (found.isEmpty()) {
                return Map.<Ruin, Set<ChunkKey>>of();
            }

            final Map<Ruin, Set<ChunkKey>> loaded = new LinkedHashMap<>();
            found.values().forEach(ruin -> loaded.put(ruin, new LinkedHashSet<>()));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT world_id, chunk_x, chunk_z, ruin_id FROM rt_ruin_claim");
                 ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    final Ruin ruin = found.get(UUID.fromString(results.getString("ruin_id")));
                    if (ruin != null) {
                        loaded.get(ruin).add(chunkOf(results));
                    }
                }
            }
            return Map.copyOf(loaded);
        });
    }

    @Override
    public List<Ruin> lapsed(final Instant now) {
        Objects.requireNonNull(now, "now");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM rt_ruin WHERE reclaimed_at IS NULL "
                            + "AND expires_at <= ? "
                            + "AND ruin_id IN (SELECT ruin_id FROM rt_ruin_claim) "
                            + "ORDER BY expires_at")) {
                statement.setLong(1, now.toEpochMilli());
                return readAll(statement);
            }
        });
    }

    @Override
    public Set<ChunkKey> chunksOf(final UUID ruinId) {
        Objects.requireNonNull(ruinId, "ruinId");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT world_id, chunk_x, chunk_z FROM rt_ruin_claim WHERE ruin_id = ?")) {
                statement.setString(1, ruinId.toString());
                final Set<ChunkKey> held = new LinkedHashSet<>();
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        held.add(chunkOf(results));
                    }
                }
                return Set.copyOf(held);
            }
        });
    }

    @Override
    public void save(final Ruin ruin, final java.util.Collection<ChunkKey> chunks) {
        Objects.requireNonNull(ruin, "ruin");
        Objects.requireNonNull(chunks, "chunks");
        StorageFailure.wrapping(() -> {
            insert(ruin);
            if (chunks.isEmpty()) {
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO rt_ruin_claim (world_id, chunk_x, chunk_z, ruin_id) "
                            + "VALUES (?, ?, ?, ?)")) {
                for (final ChunkKey chunk : chunks) {
                    statement.setString(1, chunk.worldId().toString());
                    statement.setInt(2, chunk.chunkX());
                    statement.setInt(3, chunk.chunkZ());
                    statement.setString(4, ruin.id().toString());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    @Override
    public void update(final Ruin ruin) {
        Objects.requireNonNull(ruin, "ruin");
        StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE rt_ruin SET reclaimed_at = ?, reclaimed_by = ?, reclaimed_as = ? "
                            + "WHERE ruin_id = ?")) {
                setInstant(statement, 1, ruin.reclaimedAt());
                setId(statement, 2, ruin.reclaimedBy() == null ? null : ruin.reclaimedBy().value());
                setId(statement, 3, ruin.reclaimedAs() == null ? null : ruin.reclaimedAs().value());
                statement.setString(4, ruin.id().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public int releaseLand(final UUID ruinId) {
        Objects.requireNonNull(ruinId, "ruinId");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_ruin_claim WHERE ruin_id = ?")) {
                statement.setString(1, ruinId.toString());
                return statement.executeUpdate();
            }
        });
    }

    private void insert(final Ruin ruin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rt_ruin (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, ruin.id().toString());
            statement.setString(2, ruin.formerTown().value().toString());
            statement.setString(3, ruin.name().display());
            setId(statement, 4, ruin.founder() == null ? null : ruin.founder().value());
            statement.setLong(5, ruin.ruinedAt().toEpochMilli());
            statement.setLong(6, ruin.expiresAt().toEpochMilli());
            setInstant(statement, 7, ruin.reclaimedAt());
            setId(statement, 8, ruin.reclaimedBy() == null ? null : ruin.reclaimedBy().value());
            setId(statement, 9, ruin.reclaimedAs() == null ? null : ruin.reclaimedAs().value());
            statement.executeUpdate();
        }
    }

    private static void setId(final PreparedStatement statement, final int index, final UUID value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.toString());
        }
    }

    private static void setInstant(
            final PreparedStatement statement, final int index, final Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value.toEpochMilli());
        }
    }

    private static ChunkKey chunkOf(final ResultSet results) throws SQLException {
        return new ChunkKey(
                UUID.fromString(results.getString("world_id")),
                results.getInt("chunk_x"),
                results.getInt("chunk_z"));
    }

    private static List<Ruin> readAll(final PreparedStatement statement) throws SQLException {
        final List<Ruin> rows = new ArrayList<>();
        try (ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(read(results));
            }
        }
        return List.copyOf(rows);
    }

    private static Ruin read(final ResultSet results) throws SQLException {
        final String display = results.getString("name");
        final String founder = results.getString("founder_id");
        final long reclaimedAt = results.getLong("reclaimed_at");
        final boolean reclaimed = !results.wasNull();
        final String reclaimedBy = results.getString("reclaimed_by");
        final String reclaimedAs = results.getString("reclaimed_as");

        return new Ruin(
                UUID.fromString(results.getString("ruin_id")),
                TownId.parse(results.getString("former_town_id")),
                // Rebuilt rather than stored in three columns: a ruin's name is history, never a
                // uniqueness key, so the normalised and skeleton forms are derived on read.
                new OrganisationName(
                        display,
                        display.toLowerCase(java.util.Locale.ROOT),
                        NamePolicy.skeleton(display.toLowerCase(java.util.Locale.ROOT))),
                founder == null ? null : ResidentId.parse(founder),
                Instant.ofEpochMilli(results.getLong("ruined_at")),
                Instant.ofEpochMilli(results.getLong("expires_at")),
                reclaimed ? Instant.ofEpochMilli(reclaimedAt) : null,
                reclaimedBy == null ? null : ResidentId.parse(reclaimedBy),
                reclaimedAs == null ? null : TownId.parse(reclaimedAs));
    }
}
