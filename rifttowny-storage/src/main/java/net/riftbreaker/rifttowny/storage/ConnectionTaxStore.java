package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
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
    public boolean claimPeriod(
            final String periodKey,
            final String serverId,
            final Instant now,
            final Duration staleAfter
    ) {
        Objects.requireNonNull(periodKey, "periodKey");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(staleAfter, "staleAfter");

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rt_tax_run (period_key, started_at, server_id) VALUES (?, ?, ?)")) {
            statement.setString(1, periodKey);
            statement.setLong(2, now.toEpochMilli());
            statement.setString(3, serverId);
            statement.executeUpdate();
            return true;
        } catch (final SQLException alreadyRun) {
            // A primary key violation is the answer rather than a fault: somebody has this period.
            // Distinguishing it from a real database failure by SQLState is not portable across the
            // two dialects, and the response to either is the same - do not insert.
            return takeOverIfAbandoned(periodKey, serverId, now, staleAfter);
        }
    }

    /**
     * Takes an unfinished run back over, when whoever started it plainly is not coming back.
     *
     * <p>Without this, a crash part-way through a run left the period claimed for ever: the row
     * existed, so every later attempt lost the insert and returned false, and the towns that had
     * not yet been charged never were. Silently, because nothing read {@code finished_at}.</p>
     *
     * <p>Only a row with no {@code finished_at} and a {@code started_at} older than the staleness
     * window is taken. A finished run is done and a fresh one is somebody actively working.</p>
     *
     * <p>What makes the takeover safe is not the window â a window is a guess, and on a shared
     * database the original server could always still be alive. It is that every charge inside a run
     * now claims its own key in the same transaction as the money it moves, so a second runner
     * charges only what the first had not reached. The window decides when a resume is worth trying;
     * the keys decide that trying twice cannot cost anybody twice.</p>
     */
    private boolean takeOverIfAbandoned(
            final String periodKey,
            final String serverId,
            final Instant now,
            final Duration staleAfter
    ) {
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE rt_tax_run SET started_at = ?, server_id = ? "
                            + "WHERE period_key = ? AND finished_at IS NULL AND started_at < ?")) {
                statement.setLong(1, now.toEpochMilli());
                statement.setString(2, serverId);
                statement.setString(3, periodKey);
                statement.setLong(4, now.minus(staleAfter).toEpochMilli());
                return statement.executeUpdate() == 1;
            }
        });
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
    public java.util.Optional<TaxRunRow> lastRun() {
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT period_key, started_at, finished_at, towns_charged, "
                            + "residents_charged, towns_fallen, server_id FROM rt_tax_run "
                            + "ORDER BY started_at DESC LIMIT 1");
                    ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return java.util.Optional.empty();
                }
                final long finished = results.getLong("finished_at");
                // wasNull rather than a zero check alone: a run really could finish at epoch zero
                // on a machine with a wrong clock, and reading that as "never finished" would send
                // a later attempt to take over a run that had already charged everybody.
                final boolean unfinished = results.wasNull();
                return java.util.Optional.of(new TaxRunRow(
                        results.getString("period_key"),
                        Instant.ofEpochMilli(results.getLong("started_at")),
                        unfinished ? null : Instant.ofEpochMilli(finished),
                        results.getInt("towns_charged"),
                        results.getInt("residents_charged"),
                        results.getInt("towns_fallen"),
                        results.getString("server_id")));
            }
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
