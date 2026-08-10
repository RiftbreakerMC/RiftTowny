package net.riftbreaker.rifttowny.domain.idempotency;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A claimed or completed idempotency key.
 *
 * @param key the caller-supplied key; unique
 * @param scope what kind of operation it guards, for pruning and diagnostics
 * @param result the stored outcome, or null while the operation is still in flight
 * @param createdAt when the key was claimed
 * @param completedAt when the operation finished, or null
 */
public record IdempotencyRecord(
        String key,
        String scope,
        String result,
        Instant createdAt,
        Instant completedAt
) {

    public IdempotencyRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Whether the guarded operation finished. A claimed-but-incomplete key means a crash mid-flight. */
    public boolean completed() {
        return completedAt != null;
    }

    public Optional<String> storedResult() {
        return Optional.ofNullable(result);
    }
}
