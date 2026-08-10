package net.riftbreaker.rifttowny.storage;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Runs a connection-scoped store on its own connection and completes a future with the result.
 *
 * <p>The bridge between the synchronous stores, which hold the SQL, and the asynchronous
 * repositories, which are what a caller outside a transaction uses. Reads take no write lock;
 * writes take the SQLite write lock through {@link RiftTownyDatabase#write}.</p>
 */
final class Async {

    private Async() {
    }

    static <T> CompletableFuture<T> read(
            final RiftTownyDatabase database,
            final Executor executor,
            final Function<ConnectionResidentStore, T> work
    ) {
        return run(executor, () -> database.read(connection ->
                work.apply(new ConnectionResidentStore(connection, database.backend()))));
    }

    static <T> CompletableFuture<T> write(
            final RiftTownyDatabase database,
            final Executor executor,
            final Function<ConnectionResidentStore, T> work
    ) {
        return run(executor, () -> database.write(connection ->
                work.apply(new ConnectionResidentStore(connection, database.backend()))));
    }

    static <T> CompletableFuture<T> readTowns(
            final RiftTownyDatabase database,
            final Executor executor,
            final Function<ConnectionTownStore, T> work
    ) {
        return run(executor, () -> database.read(connection ->
                work.apply(new ConnectionTownStore(connection, database.backend()))));
    }

    /**
     * Town writes run in a transaction, not a bare write: saving a town rewrites its trust rows too,
     * and a crash between the two would leave a trust list that is a mix of two saves.
     */
    static <T> CompletableFuture<T> writeTowns(
            final RiftTownyDatabase database,
            final Executor executor,
            final Function<ConnectionTownStore, T> work
    ) {
        return run(executor, () -> database.transaction(connection ->
                work.apply(new ConnectionTownStore(connection, database.backend()))));
    }

    static <T> CompletableFuture<T> readNations(
            final RiftTownyDatabase database,
            final Executor executor,
            final Function<ConnectionNationStore, T> work
    ) {
        return run(executor, () -> database.read(connection ->
                work.apply(new ConnectionNationStore(connection, database.backend()))));
    }

    /** Nation deletion releases its towns first, so it needs a transaction rather than a write. */
    static <T> CompletableFuture<T> writeNations(
            final RiftTownyDatabase database,
            final Executor executor,
            final Function<ConnectionNationStore, T> work
    ) {
        return run(executor, () -> database.transaction(connection ->
                work.apply(new ConnectionNationStore(connection, database.backend()))));
    }

    private static <T> CompletableFuture<T> run(final Executor executor, final Call<T> call) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(call.get());
            } catch (final StorageFailure wrapped) {
                // Unwrapped so a caller sees the SQLException that actually happened rather than a
                // plumbing type they would have to know about to interpret.
                future.completeExceptionally(wrapped.getCause());
            } catch (final SQLException | RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    @FunctionalInterface
    private interface Call<T> {
        T get() throws SQLException;
    }
}
