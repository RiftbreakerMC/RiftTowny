package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.bank.LedgerEntry;
import net.riftbreaker.rifttowny.domain.bank.Money;
import net.riftbreaker.rifttowny.domain.config.StorageBackend;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Civic money SQL, bound to one connection.
 *
 * <p>Amounts cross the boundary as exact decimal strings in both dialects. MariaDB's column is
 * {@code DECIMAL} and would accept a number, but going through the string form on both sides means
 * one code path and no chance of a driver silently handing back a double.</p>
 */
final class ConnectionBankStore implements CivicTransaction.BankStore {

    private static final String LEDGER_COLUMNS =
            "entry_id, account_id, currency, amount, balance, reason, actor_id, detail, occurred_at";

    private final Connection connection;
    private final StorageBackend backend;

    ConnectionBankStore(final Connection connection, final StorageBackend backend) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public Optional<Money> balance(final UUID accountId, final String currency) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currency, "currency");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT amount FROM rt_organisation_balance "
                            + "WHERE account_id = ? AND currency = ?")) {
                statement.setString(1, accountId.toString());
                statement.setString(2, currency);
                try (ResultSet results = statement.executeQuery()) {
                    return results.next()
                            ? Optional.of(Money.fromStorage(results.getString("amount"), currency))
                            : Optional.<Money>empty();
                }
            }
        });
    }

    @Override
    public void record(final LedgerEntry entry, final Instant now) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(now, "now");
        StorageFailure.wrapping(() -> {
            // The balance first, then the entry that explains it. Same transaction either way, so
            // the order is only about reading the code: the new state, then the note about it.
            final String sql = switch (backend) {
                case SQLITE -> "INSERT INTO rt_organisation_balance "
                        + "(account_id, currency, amount, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(account_id, currency) DO UPDATE SET "
                        + "amount = excluded.amount, updated_at = excluded.updated_at";
                case MARIADB -> "INSERT INTO rt_organisation_balance "
                        + "(account_id, currency, amount, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE amount = VALUES(amount), "
                        + "updated_at = VALUES(updated_at)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, entry.accountId().toString());
                statement.setString(2, entry.balance().currency());
                statement.setString(3, entry.balance().toStorage());
                statement.setLong(4, now.toEpochMilli());
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO rt_bank_ledger (" + LEDGER_COLUMNS + ") "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, entry.id().toString());
                statement.setString(2, entry.accountId().toString());
                statement.setString(3, entry.amount().currency());
                statement.setString(4, entry.amount().toStorage());
                statement.setString(5, entry.balance().toStorage());
                statement.setString(6, entry.reason().storageValue());
                if (entry.actor() == null) {
                    statement.setNull(7, Types.VARCHAR);
                } else {
                    statement.setString(7, entry.actor().value().toString());
                }
                if (entry.detail() == null) {
                    statement.setNull(8, Types.VARCHAR);
                } else {
                    statement.setString(8, entry.detail());
                }
                statement.setLong(9, entry.occurredAt().toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<LedgerEntry> history(final UUID accountId, final int limit) {
        Objects.requireNonNull(accountId, "accountId");
        return StorageFailure.wrapping(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    // By sequence, not by time: two movements in the same millisecond are ordinary,
                    // and a ledger that returns them in id order is one nobody can reconcile.
                    "SELECT " + LEDGER_COLUMNS + " FROM rt_bank_ledger WHERE account_id = ? "
                            + "ORDER BY sequence DESC LIMIT ?")) {
                statement.setString(1, accountId.toString());
                statement.setInt(2, Math.max(1, limit));
                final List<LedgerEntry> entries = new ArrayList<>();
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        read(results).ifPresent(entries::add);
                    }
                }
                return List.copyOf(entries);
            }
        });
    }


    @Override
    public List<Money> balancesOf(final UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return StorageFailure.wrapping(() -> {
            final List<Money> found = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT currency, amount FROM rt_organisation_balance WHERE account_id = ? "
                            + "ORDER BY currency")) {
                statement.setString(1, accountId.toString());
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        found.add(Money.fromStorage(
                                results.getString("amount"), results.getString("currency")));
                    }
                }
            }
            return List.copyOf(found);
        });
    }
    @Override
    public int forget(final UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return StorageFailure.wrapping(() -> {
            int removed = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_organisation_balance WHERE account_id = ?")) {
                statement.setString(1, accountId.toString());
                removed += statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rt_bank_ledger WHERE account_id = ?")) {
                statement.setString(1, accountId.toString());
                removed += statement.executeUpdate();
            }
            return removed;
        });
    }

    /** Empty for a reason this version does not know, so one row cannot stop a history loading. */
    private static Optional<LedgerEntry> read(final ResultSet results) throws SQLException {
        final Optional<LedgerEntry.Reason> reason =
                LedgerEntry.Reason.parse(results.getString("reason"));
        if (reason.isEmpty()) {
            return Optional.empty();
        }
        final String currency = results.getString("currency");
        final String actor = results.getString("actor_id");
        return Optional.of(new LedgerEntry(
                UUID.fromString(results.getString("entry_id")),
                UUID.fromString(results.getString("account_id")),
                Money.fromStorage(results.getString("amount"), currency),
                Money.fromStorage(results.getString("balance"), currency),
                reason.get(),
                actor == null ? null : ResidentId.parse(actor),
                results.getString("detail"),
                Instant.ofEpochMilli(results.getLong("occurred_at"))));
    }
}
