package net.riftbreaker.rifttowny.domain.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable, exactly-once-ish event awaiting delivery to Discord or another backend.
 *
 * <p>The outbox exists because "announce this war declaration" must not be lost when the server
 * that wrote it dies a second later, and must not be sent twice when it comes back. The row is
 * written in the same transaction as the state change it describes, so there is no window where the
 * war started but the announcement was never queued.</p>
 *
 * @param id database row id; {@code 0} before insert
 * @param eventId stable identity, unique, used for deduplication by the consumer
 * @param eventType the event name, e.g. {@code town.created}
 * @param payload the serialised body
 * @param correlationId groups related events, e.g. every event of one war
 * @param createdAt when the state change happened
 * @param availableAt when delivery may next be attempted; back-off moves it forward
 * @param attempts how many delivery attempts have been made
 * @param status current lifecycle state
 * @param claimedBy the server id holding the claim, or null
 * @param lastError the most recent failure, or null
 */
public record OutboxEvent(
        long id,
        UUID eventId,
        String eventType,
        String payload,
        String correlationId,
        Instant createdAt,
        Instant availableAt,
        int attempts,
        OutboxStatus status,
        String claimedBy,
        String lastError
) {

    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(availableAt, "availableAt");
        Objects.requireNonNull(status, "status");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative: " + attempts);
        }
    }

    /** A new event, ready to insert. */
    public static OutboxEvent pending(
            final UUID eventId,
            final String eventType,
            final String payload,
            final String correlationId,
            final Instant now
    ) {
        return new OutboxEvent(
                0L, eventId, eventType, payload, correlationId, now, now, 0,
                OutboxStatus.PENDING, null, null);
    }
}
