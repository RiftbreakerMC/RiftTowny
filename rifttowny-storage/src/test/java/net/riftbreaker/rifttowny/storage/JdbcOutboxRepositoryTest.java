package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.outbox.OutboxCounts;
import net.riftbreaker.rifttowny.domain.outbox.OutboxEvent;
import net.riftbreaker.rifttowny.domain.outbox.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcOutboxRepositoryTest extends SqliteFixture {

    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));

    private JdbcOutboxRepository outbox;

    @BeforeEach
    void createRepository() {
        outbox = new JdbcOutboxRepository(database, DIRECT, clock);
    }

    /**
     * A clock the test moves by hand.
     *
     * <p>Claim expiry is a comparison between two wall-clock instants. Testing it against the
     * system clock would mean sleeping, and a sleep-based test is the kind that passes on a laptop
     * and fails on a loaded CI agent.</p>
     */
    private static final class MutableClock extends java.time.Clock {
        private Instant now;

        private MutableClock(final Instant start) {
            this.now = start;
        }

        void advance(final Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Appends at the test clock's instant, so "available now" means the same to the repository. */
    private OutboxEvent append(final String type, final String payload) {
        return outbox.append(OutboxEvent.pending(
                UUID.randomUUID(), type, payload, "correlation-1", clock.instant())).join();
    }

    @Test
    @DisplayName("an appended event is stored as pending")
    void appendStoresPending() {
        final OutboxEvent stored = append("town.created", "{\"town\":\"Riftholm\"}");

        assertThat(stored.id()).isPositive();
        assertThat(stored.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(stored.attempts()).isZero();
        assertThat(outbox.find(stored.eventId()).join()).isPresent();
    }

    @Test
    @DisplayName("appending the same event id twice inserts once, so a retried mutation cannot double-announce")
    void appendIsIdempotentOnEventId() {
        final UUID eventId = UUID.randomUUID();
        final Instant now = clock.instant();

        final OutboxEvent first = outbox.append(
                OutboxEvent.pending(eventId, "war.declared", "first", "war-1", now)).join();
        final OutboxEvent second = outbox.append(
                OutboxEvent.pending(eventId, "war.declared", "second", "war-1", now)).join();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.payload())
                .as("the first write wins; a retry must not overwrite the payload")
                .isEqualTo("first");
        assertThat(outbox.counts().join().total()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a claimed event is invisible to a second server")
    void claimIsExclusiveAcrossServers() {
        append("town.created", "a");
        append("town.created", "b");

        final List<OutboxEvent> first = outbox.claimBatch("survival-1", 10, CLAIM_TIMEOUT).join();
        final List<OutboxEvent> second = outbox.claimBatch("survival-2", 10, CLAIM_TIMEOUT).join();

        assertThat(first).hasSize(2);
        assertThat(first).allSatisfy(event -> {
            assertThat(event.status()).isEqualTo(OutboxStatus.CLAIMED);
            assertThat(event.claimedBy()).isEqualTo("survival-1");
        });
        assertThat(second)
                .as("survival-2 must not be handed events survival-1 already holds")
                .isEmpty();
    }

    @Test
    @DisplayName("a stale claim is reclaimable, so a crashed server cannot strand an event forever")
    void staleClaimsAreReclaimed() {
        append("nation.created", "payload");

        assertThat(outbox.claimBatch("survival-1", 10, CLAIM_TIMEOUT).join()).hasSize(1);

        // survival-1 dies here. Nothing releases the claim; only the timeout does.
        assertThat(outbox.claimBatch("survival-2", 10, CLAIM_TIMEOUT).join())
                .as("the claim is still fresh, so nobody else may take it yet")
                .isEmpty();

        clock.advance(CLAIM_TIMEOUT.plusSeconds(1));
        final List<OutboxEvent> reclaimed = outbox.claimBatch("survival-2", 10, CLAIM_TIMEOUT).join();

        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.getFirst().claimedBy()).isEqualTo("survival-2");
    }

    @Test
    @DisplayName("claimBatch honours its limit")
    void claimRespectsLimit() {
        append("a", "1");
        append("b", "2");
        append("c", "3");

        assertThat(outbox.claimBatch("survival-1", 2, CLAIM_TIMEOUT).join()).hasSize(2);
    }

    @Test
    @DisplayName("draining the queue never re-hands events the caller already holds")
    void repeatedClaimsInTheSameMillisecondDoNotOverlap() {
        append("a", "1");
        append("b", "2");
        append("c", "3");
        append("d", "4");

        // Same server, same clock instant - a dispatcher looping to drain the queue. The claim
        // metadata is identical for both calls, so reading the batch back by (claimed_by,
        // claimed_at, status) would return the first two events a second time and announce them
        // twice.
        final List<OutboxEvent> first = outbox.claimBatch("survival-1", 2, CLAIM_TIMEOUT).join();
        final List<OutboxEvent> second = outbox.claimBatch("survival-1", 2, CLAIM_TIMEOUT).join();

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(second).extracting(OutboxEvent::eventId)
                .as("the second batch must not contain anything from the first")
                .doesNotContainAnyElementsOf(first.stream().map(OutboxEvent::eventId).toList());
    }

    @Test
    @DisplayName("a delivered event leaves the queue and releases its claim")
    void deliveryClearsTheClaim() {
        final OutboxEvent event = append("town.created", "payload");
        outbox.claimBatch("survival-1", 10, CLAIM_TIMEOUT).join();

        outbox.markDelivered(event.eventId()).join();

        final OutboxEvent reloaded = outbox.find(event.eventId()).join().orElseThrow();
        assertThat(reloaded.status()).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(reloaded.claimedBy()).isNull();
        assertThat(outbox.claimBatch("survival-2", 10, CLAIM_TIMEOUT).join()).isEmpty();
    }

    @Test
    @DisplayName("a failure retries until the budget is spent, then parks the event for a human")
    void failureRetriesThenParks() {
        final OutboxEvent event = append("war.declared", "payload");
        final Instant retryAt = clock.instant().minusSeconds(1);

        outbox.markFailed(event.eventId(), "webhook 503", retryAt, 3).join();
        OutboxEvent reloaded = outbox.find(event.eventId()).join().orElseThrow();
        assertThat(reloaded.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reloaded.attempts()).isEqualTo(1);
        assertThat(reloaded.lastError()).isEqualTo("webhook 503");

        outbox.markFailed(event.eventId(), "webhook 503", retryAt, 3).join();
        outbox.markFailed(event.eventId(), "webhook 503", retryAt, 3).join();

        reloaded = outbox.find(event.eventId()).join().orElseThrow();
        assertThat(reloaded.status()).isEqualTo(OutboxStatus.FAILED);
        assertThat(reloaded.attempts()).isEqualTo(3);
        assertThat(outbox.claimBatch("survival-1", 10, CLAIM_TIMEOUT).join())
                .as("a parked event must not be picked up again automatically")
                .isEmpty();
        assertThat(outbox.counts().join().healthy()).isFalse();
    }

    @Test
    @DisplayName("an event scheduled for the future is not claimable yet")
    void futureEventsAreNotClaimable() {
        outbox.append(new OutboxEvent(
                0L, UUID.randomUUID(), "shield.expired", "payload", null,
                clock.instant(), clock.instant().plusSeconds(600), 0,
                OutboxStatus.PENDING, null, null)).join();

        assertThat(outbox.claimBatch("survival-1", 10, CLAIM_TIMEOUT).join()).isEmpty();
    }

    @Test
    @DisplayName("pruning removes delivered events only")
    void pruneRemovesDeliveredOnly() {
        final OutboxEvent delivered = append("a", "1");
        append("b", "2");
        outbox.markDelivered(delivered.eventId()).join();

        final int removed = outbox.pruneDelivered(clock.instant().plusSeconds(60)).join();

        assertThat(removed).isEqualTo(1);
        final OutboxCounts counts = outbox.counts().join();
        assertThat(counts.delivered()).isZero();
        assertThat(counts.pending()).isEqualTo(1L);
    }
}
