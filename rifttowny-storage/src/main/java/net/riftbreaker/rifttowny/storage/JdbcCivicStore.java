package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.outbox.OutboxEvent;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;
import net.riftbreaker.rifttowny.domain.store.CivicWork;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Runs civic work in one transaction.
 *
 * <p>The state change and the outbox rows it produced commit together or not at all. That is the
 * whole point: an announcement queued for a town founding that then rolled back would report a town
 * that does not exist, and a founding that committed without its announcement would be invisible to
 * Discord and to every other backend server.</p>
 */
public final class JdbcCivicStore implements CivicStore {

    private final RiftTownyDatabase database;
    private final Executor executor;
    private final Clock clock;

    public JdbcCivicStore(final RiftTownyDatabase database, final Executor executor) {
        this(database, executor, Clock.systemUTC());
    }

    /** @param clock injected so a test can assert the timestamps written to the outbox */
    public JdbcCivicStore(
            final RiftTownyDatabase database, final Executor executor, final Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public <T> CompletableFuture<T> inTransaction(final CivicWork<T> work) {
        Objects.requireNonNull(work, "work");
        final CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(database.transaction(connection -> {
                    try {
                        return work.perform(new JdbcCivicTransaction(connection, database, clock));
                    } catch (final SQLException | RuntimeException failure) {
                        throw failure;
                    } catch (final Exception failure) {
                        // CivicWork may throw anything. Wrapped so RiftTownyDatabase.transaction,
                        // which rolls back on SQLException and RuntimeException, actually sees it.
                        throw new IllegalStateException(failure);
                    }
                }));
            } catch (final StorageFailure wrapped) {
                // Unwrapped so the caller sees the SQLException that actually happened rather than
                // a plumbing type they would have to know about to interpret.
                future.completeExceptionally(wrapped.getCause());
            } catch (final Throwable failure) {
                // Throwable so the future is always completed, even for an Error. Otherwise the
                // player's command never gets a reply and anything joining on it blocks forever.
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    /** One open transaction. Package-private: only this store may construct one. */
    private static final class JdbcCivicTransaction implements CivicTransaction {

        private final Connection connection;
        private final Clock clock;
        private final ConnectionResidentStore residents;
        private final ConnectionTownStore towns;
        private final ConnectionNationStore nations;
        private final ConnectionRoleStore roles;
        private final ConnectionClaimStore claims;
        private final ConnectionFlagStore flags;
        private final ConnectionInvitationStore invitations;
        private final ConnectionRuinStore ruins;
        private final ConnectionSpawnStore spawns;
        private final ConnectionBankStore bank;
        private final ConnectionTaxStore taxes;
        private final ConnectionRelationStore relations;
        private final ConnectionOutlawStore outlaws;

        private JdbcCivicTransaction(
                final Connection connection, final RiftTownyDatabase database, final Clock clock) {
            this.connection = connection;
            this.clock = clock;
            this.residents = new ConnectionResidentStore(connection, database.backend());
            this.towns = new ConnectionTownStore(connection, database.backend());
            this.nations = new ConnectionNationStore(connection, database.backend());
            this.roles = new ConnectionRoleStore(connection);
            this.claims = new ConnectionClaimStore(connection);
            this.flags = new ConnectionFlagStore(connection, database.backend());
            this.invitations = new ConnectionInvitationStore(connection, database.backend());
            this.ruins = new ConnectionRuinStore(connection);
            this.spawns = new ConnectionSpawnStore(connection, database.backend());
            this.bank = new ConnectionBankStore(connection, database.backend());
            this.taxes = new ConnectionTaxStore(connection);
            this.relations = new ConnectionRelationStore(connection, database.backend());
            this.outlaws = new ConnectionOutlawStore(connection, database.backend());
        }

        @Override
        public ResidentStore residents() {
            return residents;
        }

        @Override
        public TownStore towns() {
            return towns;
        }

        @Override
        public NationStore nations() {
            return nations;
        }

        @Override
        public RoleStore roles() {
            return roles;
        }

        @Override
        public ClaimStore claims() {
            return claims;
        }

        @Override
        public FlagStore flags() {
            return flags;
        }

        @Override
        public InvitationStore invitations() {
            return invitations;
        }

        @Override
        public RuinStore ruins() {
            return ruins;
        }

        @Override
        public SpawnStore spawns() {
            return spawns;
        }

        @Override
        public BankStore bank() {
            return bank;
        }

        @Override
        public TaxStore taxes() {
            return taxes;
        }

        @Override
        public RelationStore relations() {
            return relations;
        }

        @Override
        public OutlawStore outlaws() {
            return outlaws;
        }

        @Override
        public void publish(final DomainEvent event, final String correlationId) {
            Objects.requireNonNull(event, "event");
            StorageFailure.wrapping(() -> {
                // A fresh event id per publish. Deduplication upstream is the caller's job through
                // the idempotency store; here, two publishes mean two things happened.
                final OutboxEvent row = OutboxEvent.pending(
                        UUID.randomUUID(), event.type(), event.toString(), correlationId,
                        clock.instant());
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO rt_outbox (event_id, event_type, payload, correlation_id, "
                                + "created_at, available_at, attempts, status) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    statement.setString(1, row.eventId().toString());
                    statement.setString(2, row.eventType());
                    statement.setString(3, row.payload());
                    if (correlationId == null) {
                        statement.setNull(4, Types.VARCHAR);
                    } else {
                        statement.setString(4, correlationId);
                    }
                    statement.setLong(5, row.createdAt().toEpochMilli());
                    statement.setLong(6, row.availableAt().toEpochMilli());
                    statement.setInt(7, 0);
                    statement.setString(8, row.status().name());
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }
}
