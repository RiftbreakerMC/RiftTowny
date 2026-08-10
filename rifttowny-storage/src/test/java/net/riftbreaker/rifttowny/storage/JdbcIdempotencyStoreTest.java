package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.idempotency.IdempotencyRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcIdempotencyStoreTest extends SqliteFixture {

    private JdbcIdempotencyStore keys;

    @BeforeEach
    void createStore() {
        keys = new JdbcIdempotencyStore(database, DIRECT);
    }

    @Test
    @DisplayName("only the first caller wins a key, so a retried payment runs once")
    void onlyTheFirstClaimWins() {
        final Instant now = Instant.now();

        assertThat(keys.claim("tax:town-1:2026-08-09", "tax", now).join()).isTrue();
        assertThat(keys.claim("tax:town-1:2026-08-09", "tax", now).join()).isFalse();
    }

    @Test
    @DisplayName("a completed key stores its result for the retry to read back")
    void completionStoresTheResult() {
        keys.claim("claim:town-1:42", "claim-purchase", Instant.now()).join();
        keys.complete("claim:town-1:42", "receipt-9001").join();

        final IdempotencyRecord record = keys.find("claim:town-1:42").join().orElseThrow();
        assertThat(record.completed()).isTrue();
        assertThat(record.storedResult()).contains("receipt-9001");
        assertThat(record.scope()).isEqualTo("claim-purchase");
    }

    @Test
    @DisplayName("releasing an incomplete claim lets the operation be retried after a crash")
    void releaseFreesAnIncompleteClaim() {
        keys.claim("war:settle:1", "war", Instant.now()).join();

        keys.release("war:settle:1").join();

        assertThat(keys.find("war:settle:1").join()).isEmpty();
        assertThat(keys.claim("war:settle:1", "war", Instant.now()).join()).isTrue();
    }

    @Test
    @DisplayName("releasing a completed key does nothing, so the guard cannot be undone")
    void releaseWillNotUndoACompletedOperation() {
        keys.claim("tribute:1", "war", Instant.now()).join();
        keys.complete("tribute:1", "paid").join();

        keys.release("tribute:1").join();

        assertThat(keys.find("tribute:1").join()).isPresent();
        assertThat(keys.claim("tribute:1", "war", Instant.now()).join())
                .as("a completed operation must never become claimable again")
                .isFalse();
    }

    @Test
    @DisplayName("pruning removes completed keys only, never one still in flight")
    void pruneLeavesInFlightKeysAlone() {
        final Instant old = Instant.now().minusSeconds(3_600);
        keys.claim("done", "tax", old).join();
        keys.complete("done", "ok").join();
        keys.claim("in-flight", "tax", old).join();

        final int removed = keys.prune(Instant.now()).join();

        assertThat(removed).isEqualTo(1);
        assertThat(keys.find("done").join()).isEmpty();
        assertThat(keys.find("in-flight").join()).isPresent();
    }
}
