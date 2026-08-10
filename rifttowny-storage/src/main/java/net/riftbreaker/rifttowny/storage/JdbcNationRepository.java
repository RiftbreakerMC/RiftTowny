package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.NationRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Asynchronous nations, for single-operation callers.
 *
 * <p>Holds no SQL of its own; {@link ConnectionNationStore} does, and a service running a
 * multi-aggregate transaction runs the same class.</p>
 */
public final class JdbcNationRepository implements NationRepository {

    private final RiftTownyDatabase database;
    private final Executor executor;

    public JdbcNationRepository(final RiftTownyDatabase database, final Executor executor) {
        this.database = Objects.requireNonNull(database, "database");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<Optional<Nation>> find(final NationId id) {
        return Async.readNations(database, executor, store -> store.find(id));
    }

    @Override
    public CompletableFuture<Optional<Nation>> findByName(final String name) {
        return Async.readNations(database, executor, store -> store.findByName(name));
    }

    @Override
    public CompletableFuture<Nation> save(final Nation nation) {
        Objects.requireNonNull(nation, "nation");
        return Async.writeNations(database, executor, store -> {
            store.save(nation);
            return nation;
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(final NationId id) {
        return Async.writeNations(database, executor, store -> store.delete(id));
    }

    @Override
    public CompletableFuture<Integer> count() {
        return Async.readNations(database, executor, ConnectionNationStore::count);
    }

    @Override
    public CompletableFuture<List<Nation>> all() {
        return Async.readNations(database, executor, ConnectionNationStore::all);
    }
}
