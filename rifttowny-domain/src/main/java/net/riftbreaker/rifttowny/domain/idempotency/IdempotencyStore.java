package net.riftbreaker.rifttowny.domain.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Remembers that an operation already happened, so a retry does not repeat it.
 *
 * <p>Needed anywhere a duplicate is expensive rather than merely untidy: paying a tax, transferring
 * land at a war settlement, charging for a claim, extending a shield. A network timeout does not
 * tell the caller whether the operation ran, so the caller retries; the key is what makes that
 * safe.</p>
 */
public interface IdempotencyStore {

    /**
     * Claims a key for a first execution.
     *
     * @return {@code true} if this caller won the claim and should perform the operation;
     *         {@code false} if the key already existed, meaning the operation already ran
     */
    CompletableFuture<Boolean> claim(String key, String scope, Instant now);

    /** Stores the outcome of a completed operation so a retry can return it instead of re-running. */
    CompletableFuture<Void> complete(String key, String result);

    /** The stored record for a key, if any. */
    CompletableFuture<Optional<IdempotencyRecord>> find(String key);

    /**
     * Releases a claim whose operation failed before completing.
     *
     * <p>Without this, a crash between {@link #claim} and {@link #complete} would block the
     * operation forever — the key would exist with no result and every retry would be refused.</p>
     */
    CompletableFuture<Void> release(String key);

    /** Deletes records older than {@code before}. Returns how many rows went. */
    CompletableFuture<Integer> prune(Instant before);
}
