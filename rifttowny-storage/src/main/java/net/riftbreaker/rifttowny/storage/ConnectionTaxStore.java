package net.riftbreaker.rifttowny.storage;

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

/** Tax run SQL, bound to one connection. */
final class ConnectionTaxStore implements CivicTransaction.TaxStore {

    private final Connection connection;

    ConnectionTaxStore(final Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public boolean claimPeriod(final String periodKey, final String serverId, final Instant now) {
        Objects.requireNonNull(periodKey, "periodKey");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(now, "now");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rt_tax_run (period_key, started_at, server_id) VALUES (?, ?, ?)")) {
            statement.setString(1, periodKey);
            statement.setLong(2, now.toEpochMilli());
            statement.setString(3, serverId);
            statement.executeUpdate();
            return true;
        } catch (final SQLException alreadyRun) {
            // A primary key violation is the answer rather than a fault: somebody else has this
            // period. Distinguishing it from a real database failure by SQLState is not portable
            // across the two dialects, and the caller's response to either is the same - do not run.
            return false;
        }
    }

    @Override
    public void finishRun(
            final String periodKey,
            final int townsCharged,
            final int residentsCharged,
            final int townsFallen,
            final Instant now
    ) {
        Objects.requireNonNull(periodKey, "periodKey");
        StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE rt_tax_run SET finished_at = ?, towns_charged = ?, "
                            + "residents_charged = ?, towns_fallen = ? WHERE period_key = ?")) {
                statement.setLong(1, now.toEpochMilli());
                statement.setInt(2, townsCharged);
                statement.setInt(3, residentsCharged);
                statement.setInt(4, townsFallen);
                statement.setString(5, periodKey);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Instant> unpaidSince(final TownId town) {
        Objects.requireNonNull(town, "town");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT unpaid_since FROM rt_town WHERE town_id = ?")) {
                statement.setString(1, town.value().toString());
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) {
                        return Optional.<Instant>empty();
                    }
                    final long since = results.getLong("unpaid_since");
                    return results.wasNull()
                            ? Optional.<Instant>empty()
                            : Optional.of(Instant.ofEpochMilli(since));
                }
            }
        });
    }

    @Override
    public void markUnpaid(final TownId town, final Instant since) {
        Objects.requireNonNull(town, "town");
        StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE rt_town SET unpaid_since = ? WHERE town_id = ?")) {
                if (since == null) {
                    statement.setNull(1, Types.BIGINT);
                } else {
                    statement.setLong(1, since.toEpochMilli());
                }
                statement.setString(2, town.value().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<TownId> allTowns() {
        return StorageFailure.wrapping(() -> {
            // Ids only. A run visits every town and loads each one as it goes; reading the whole
            // aggregate for all of them up front would hold a server's entire civic state in memory
            // to charge them one at a time.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT town_id FROM rt_town ORDER BY created_at, town_id");
                 ResultSet results = statement.executeQuery()) {
                final List<TownId> towns = new ArrayList<>();
                while (results.next()) {
                    towns.add(TownId.parse(results.getString("town_id")));
                }
                return List.copyOf(towns);
            }
        });
    }
}
