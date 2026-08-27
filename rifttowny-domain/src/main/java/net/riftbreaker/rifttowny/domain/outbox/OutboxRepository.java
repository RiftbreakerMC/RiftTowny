package net.riftbreaker.rifttowny.domain.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Durable delivery queue for cross-server and Discord events.
 *
 * <p>Every method is asynchronous. There is no synchronous variant, deliberately: a blocking call
 * on the server thread is the single easiest way to stall a Minecraft server, and offering one
 * would guarantee somebody used it.</p>
 */
public interface OutboxRepository {

    /**
     * Appends an event.
     *
     * <p>Idempotent on {@link OutboxEvent#eventId()}: appending the same event id twice inserts
     * once. This is what makes a retried mutation safe.</p>
     *
     * @return the stored event, whether it was inserted now or already present
     */
    CompletableFuture<OutboxEvent> append(OutboxEvent event);

    /**
     * Claims up to {@code limit} deliverable events for this server.
     *
     * <p>Claiming is exclusive across the network: a row claimed by one backend is invisible to
     * another until the claim expires. That expiry is what stops a crashed server from stranding
     * events forever.</p>
     *
     * @param serverId the claiming server
     * @param limit maximum rows to claim
     * @param claimTimeout how long the claim holds before another server may take the row
     */
    CompletableFuture<List<OutboxEvent>> claimBatch(String serverId, int limit, Duration claimTimeout);

    /** Marks an event delivered. */
    CompletableFuture<Void> markDelivered(UUID eventId);

    /**
     * Records a failed attempt and schedules a retry.
     *
     * <p>When {@code attempts} would exceed {@code maxAttempts} the row becomes
     * {@link OutboxStatus#FAILED} and is left for an operator rather than dropped.</p>
     */
    CompletableFuture<Void> markFailed(UUID eventId, String error, Instant retryAt, int maxAttempts);

    /** Looks an event up by its stable id. */
    CompletableFuture<Optional<OutboxEvent>> find(UUID eventId);

    /** Deletes delivered events older than {@code before}. Returns how many rows went. */
    CompletableFuture<Integer> pruneDelivered(Instant before);

    /**
     * Deletes events older than {@code before} that were never delivered. Returns how many went.
     *
     * <p>Only {@link OutboxStatus#PENDING}. A {@code FAILED} row is a question somebody asked the
     * server to answer and is left alone, exactly as {@link #markFailed} promises.</p>
     *
     * <p>This exists because the queue has a writer and no drain: every civic change appends a row,
     * and the consumer that would deliver them is a separate module that has not shipped. Without
     * it the table is an unbounded leak on any server that runs for a season, and a queue nobody is
     * reading is not a queue. An announcement that has been sitting undelivered for a week has no
     * audience left, so dropping it costs nothing that keeping it would recover.</p>
     */
    CompletableFuture<Integer> pruneUndelivered(Instant before);

    /** How many rows sit in each status, for {@code /rifttowny status}. */
    CompletableFuture<OutboxCounts> counts();
}
