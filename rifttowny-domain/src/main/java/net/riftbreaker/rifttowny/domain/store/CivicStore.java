package net.riftbreaker.rifttowny.domain.store;

import java.util.concurrent.CompletableFuture;

/**
 * Runs work against the database in a single transaction.
 *
 * <p>The one way a service changes more than one thing. Either everything the work did is committed,
 * including the outbox rows it published, or none of it is.</p>
 */
public interface CivicStore {

    /**
     * Runs {@code work} inside one transaction, off any server thread.
     *
     * <p>A thrown exception rolls the transaction back and completes the returned future
     * exceptionally. Returning normally commits.</p>
     *
     * <p>Note what this does <em>not</em> do: it does not inspect the returned value. A service that
     * wants to abort must throw, not return a denial — otherwise a refused change would commit the
     * partial writes made before the refusal was discovered. {@link CivicWork} names that rule.</p>
     */
    <T> CompletableFuture<T> inTransaction(CivicWork<T> work);
}
