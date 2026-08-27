package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.ResidentRepository;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Asynchronous residents, for single-operation callers.
 *
 * <p>Holds no SQL of its own. Each method opens one connection and runs
 * {@link ConnectionResidentStore} on it — the same class a service uses inside a multi-aggregate
 * transaction — so there is one copy of every statement rather than two that drift.</p>
 *
 * <p>Use this when the change is genuinely one row. When it spans aggregates, use
 * {@link net.riftbreaker.rifttowny.domain.store.CivicStore} instead, or a crash between the two
 * writes will leave them disagreeing.</p>
 */
public final class JdbcResidentRepository implements ResidentRepository {

    private final RiftTownyDatabase database;
    private final Executor executor;

    public JdbcResidentRepository(final RiftTownyDatabase database, final Executor executor) {
        this.database = Objects.requireNonNull(database, "database");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<Optional<Resident>> find(final ResidentId id) {
        return Async.read(database, executor, store -> store.find(id));
    }

    @Override
    public CompletableFuture<Resident> save(final Resident resident) {
        Objects.requireNonNull(resident, "resident");
        return Async.write(database, executor, store -> {
            store.save(resident);
            return resident;
        });
    }

    @Override
    public CompletableFuture<List<Resident>> findByTown(final TownId town) {
        return Async.read(database, executor, store -> store.findByTown(town));
    }

    @Override
    public CompletableFuture<Optional<Resident>> findByName(final String name) {
        return Async.read(database, executor, store -> store.findByName(name));
    }
}
