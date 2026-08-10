package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.org.TownRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Asynchronous towns, for single-operation callers.
 *
 * <p>Holds no SQL of its own; {@link ConnectionTownStore} does, and a service running a
 * multi-aggregate transaction runs the same class. Use
 * {@link net.riftbreaker.rifttowny.domain.store.CivicStore} when the change spans aggregates.</p>
 */
public final class JdbcTownRepository implements TownRepository {

    private final RiftTownyDatabase database;
    private final Executor executor;

    public JdbcTownRepository(final RiftTownyDatabase database, final Executor executor) {
        this.database = Objects.requireNonNull(database, "database");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<Optional<Town>> find(final TownId id) {
        return Async.readTowns(database, executor, store -> store.find(id));
    }

    @Override
    public CompletableFuture<Optional<Town>> findByName(final String name) {
        return Async.readTowns(database, executor, store -> store.findByName(name));
    }

    @Override
    public CompletableFuture<Town> save(final Town town) {
        Objects.requireNonNull(town, "town");
        return Async.writeTowns(database, executor, store -> {
            store.save(town);
            return town;
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(final TownId id) {
        return Async.writeTowns(database, executor, store -> store.delete(id));
    }

    @Override
    public CompletableFuture<List<Town>> findByNation(final NationId nation) {
        return Async.readTowns(database, executor, store -> store.findByNation(nation));
    }

    @Override
    public CompletableFuture<Integer> count() {
        return Async.readTowns(database, executor, ConnectionTownStore::count);
    }
}
